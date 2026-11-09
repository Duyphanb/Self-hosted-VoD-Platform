package com.vodplatform.worker.transcode;

import static com.vodplatform.worker.probe.WorkerFailureCode.ENCODE_FAILED;

import com.vodplatform.worker.probe.WorkerFailureException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The output validation the worker runs before READY (ADR-010 D27, SECURITY.md Media Processing Safeguards).
 * FFmpeg's exit status is required but not sufficient: it exits 0 on a truncated faststart input and writes a
 * shorter HLS that still ends with {@code #EXT-X-ENDLIST}. The output passes only when
 * <ul>
 *   <li>{@code master.m3u8} is a regular file starting with {@code #EXTM3U}, with
 *       {@code #EXT-X-PLAYLIST-TYPE:VOD} and {@code #EXT-X-ENDLIST};</li>
 *   <li>it lists at least one segment, every URI matches {@code ^segments/[A-Za-z0-9._-]+\.ts$}, appears once
 *       and names an existing non-empty regular file;</li>
 *   <li>no unreferenced file, directory or link is present under the HLS directory;</li>
 *   <li>the sum of the EXTINF durations is within 1 second of the D26 media duration.</li>
 * </ul>
 * A tag that references another resource ({@code URI=}, a variant stream) is outside the single MPEG-TS media
 * playlist profile and fails too. Any failure is {@code ENCODE_FAILED}; its message is a fixed summary that
 * never quotes the playlist or a path. The test-support {@code HlsOutputCheck} restates these checks
 * independently, so the two can check each other.
 */
public final class HlsOutputValidator {
    /** A 3-hour source at 2-second segments lists 5400 segments, well under 1 MiB. */
    static final int MAX_PLAYLIST_BYTES = 1024 * 1024;
    static final BigDecimal DURATION_TOLERANCE_SECONDS = BigDecimal.ONE;
    private static final Pattern SEGMENT_URI = Pattern.compile("^segments/[A-Za-z0-9._-]+\\.ts$");
    private static final Pattern EXTINF_SECONDS = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,9})?");
    private static final Pattern RESOURCE_REFERENCE = Pattern.compile("[:,]\\s*(?:[A-Z0-9-]+-)?URI\\s*=");
    private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);

    private HlsOutputValidator() {
    }

    /**
     * @param hlsDirectory  the directory FFmpeg wrote, holding {@code master.m3u8} and {@code segments/}
     * @param mediaDuration the D26 media duration the probe returned
     * @throws WorkerFailureException {@code ENCODE_FAILED} when any check fails
     */
    public static HlsOutput validate(Path hlsDirectory, Duration mediaDuration) {
        Objects.requireNonNull(hlsDirectory, "hlsDirectory");
        Objects.requireNonNull(mediaDuration, "mediaDuration");
        try {
            return check(hlsDirectory, mediaDuration);
        } catch (IOException | UncheckedIOException e) {
            throw new WorkerFailureException(ENCODE_FAILED, "HLS output could not be read", e);
        }
    }

    private static HlsOutput check(Path hlsDirectory, Duration mediaDuration) throws IOException {
        Path playlistFile = hlsDirectory.resolve(FfmpegCommand.PLAYLIST);
        Path segmentsDirectory = hlsDirectory.resolve(FfmpegCommand.SEGMENTS);
        if (!Files.isDirectory(hlsDirectory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(segmentsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("HLS output directories are missing");
        }
        if (!Files.isRegularFile(playlistFile, LinkOption.NOFOLLOW_LINKS)) throw invalid("HLS playlist is missing");
        if (Files.size(playlistFile) > MAX_PLAYLIST_BYTES) throw invalid("HLS playlist exceeds its size bound");
        Playlist playlist = Playlist.parse(Files.readAllBytes(playlistFile));

        List<Path> segments = new ArrayList<>(playlist.uris().size());
        for (String uri : playlist.uris()) {
            Path segment = hlsDirectory.resolve(uri);
            if (!Files.isRegularFile(segment, LinkOption.NOFOLLOW_LINKS) || Files.size(segment) == 0) {
                throw invalid("HLS segment is missing or empty");
            }
            segments.add(segment);
        }
        Set<Path> expected = new HashSet<>(segments);
        expected.add(playlistFile);
        try (Stream<Path> entries = Files.walk(hlsDirectory)) {
            boolean unexpected = entries.anyMatch(entry -> !entry.equals(hlsDirectory) && !entry.equals(segmentsDirectory)
                    && !expected.contains(entry));
            if (unexpected) throw invalid("HLS directory holds an unreferenced file");
        }
        BigDecimal media = seconds(mediaDuration);
        if (playlist.totalSeconds().subtract(media).abs().compareTo(DURATION_TOLERANCE_SECONDS) > 0) {
            throw invalid("HLS playlist length is not within 1 second of the media duration");
        }
        return new HlsOutput(hlsDirectory, playlistFile, segments, toDuration(playlist.totalSeconds()));
    }

    private static BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano()).divide(NANOS_PER_SECOND));
    }

    private static Duration toDuration(BigDecimal seconds) {
        long whole = seconds.longValue();
        return Duration.ofSeconds(whole, seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValue());
    }

    private static WorkerFailureException invalid(String reason) {
        return new WorkerFailureException(ENCODE_FAILED, reason);
    }

    /** The parts of a single VOD media playlist the D27 checks need. */
    private record Playlist(List<String> uris, BigDecimal totalSeconds) {
        static Playlist parse(byte[] bytes) {
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                throw invalid("HLS playlist is not UTF-8 text");
            }
            String[] lines = text.split("\r?\n", -1);
            if (!lines[0].equals("#EXTM3U")) throw invalid("HLS playlist does not start with #EXTM3U");
            boolean vod = false;
            boolean endList = false;
            BigDecimal pending = null;
            BigDecimal total = BigDecimal.ZERO;
            List<String> uris = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF:")) {
                    if (pending != null) throw invalid("HLS playlist has an EXTINF without a segment");
                    pending = extinfSeconds(line.substring("#EXTINF:".length()));
                } else if (line.startsWith("#EXT-X-PLAYLIST-TYPE:")) {
                    if (!line.equals("#EXT-X-PLAYLIST-TYPE:VOD")) throw invalid("HLS playlist is not a VOD playlist");
                    vod = true;
                } else if (line.equals("#EXT-X-ENDLIST")) {
                    endList = true;
                } else if (line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                        || (line.startsWith("#EXT") && RESOURCE_REFERENCE.matcher(line).find())) {
                    throw invalid("HLS playlist references a resource outside the segment profile");
                } else if (!line.startsWith("#")) {
                    if (pending == null) throw invalid("HLS playlist has a segment without an EXTINF");
                    if (endList) throw invalid("HLS playlist lists a segment after #EXT-X-ENDLIST");
                    if (!SEGMENT_URI.matcher(line).matches()) throw invalid("HLS segment URI is not a relative segments/*.ts name");
                    if (!seen.add(line)) throw invalid("HLS playlist lists a segment twice");
                    uris.add(line);
                    total = total.add(pending);
                    pending = null;
                }
            }
            if (pending != null) throw invalid("HLS playlist has an EXTINF without a segment");
            if (!vod) throw invalid("HLS playlist is not a VOD playlist");
            if (!endList) throw invalid("HLS playlist has no #EXT-X-ENDLIST");
            if (uris.isEmpty()) throw invalid("HLS playlist lists no segment");
            return new Playlist(List.copyOf(uris), total);
        }

        private static BigDecimal extinfSeconds(String value) {
            int comma = value.indexOf(',');
            String seconds = comma < 0 ? value : value.substring(0, comma);
            if (!EXTINF_SECONDS.matcher(seconds).matches()) throw invalid("HLS playlist has an invalid EXTINF duration");
            return new BigDecimal(seconds);
        }
    }
}
