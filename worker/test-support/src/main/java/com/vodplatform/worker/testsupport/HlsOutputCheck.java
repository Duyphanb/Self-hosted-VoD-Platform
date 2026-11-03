package com.vodplatform.worker.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Test-side restatement of the output checks the worker runs before READY (ADR-010 D27, SECURITY.md
 * Media Processing Safeguards), plus the segment-duration expectations of #123. Each method returns
 * the violations it found; an empty list means the output passes.
 */
public final class HlsOutputCheck {
    public static final String PLAYLIST = "master.m3u8";
    public static final double DURATION_TOLERANCE_SECONDS = 1.0;
    private static final Pattern SEGMENT_URI = Pattern.compile("^segments/[A-Za-z0-9._-]+\\.ts$");

    private HlsOutputCheck() {
    }

    /** The D27 checks against the D26 media duration (the probed duration of the selected video stream). */
    public static List<String> d27Violations(Path hlsDirectory, double mediaDurationSeconds) throws IOException {
        Path playlistFile = hlsDirectory.resolve(PLAYLIST);
        if (!Files.isRegularFile(playlistFile, LinkOption.NOFOLLOW_LINKS)) return List.of("missing " + PLAYLIST);
        HlsPlaylist playlist;
        try {
            playlist = HlsPlaylist.parse(Files.readString(playlistFile, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
        List<String> violations = new ArrayList<>();
        if (!"VOD".equals(playlist.playlistType())) violations.add("playlist type is not VOD");
        if (!playlist.endList()) violations.add("missing #EXT-X-ENDLIST");
        if (playlist.segments().isEmpty()) violations.add("no segments");
        Set<Path> referenced = new HashSet<>();
        for (HlsPlaylist.Segment segment : playlist.segments()) {
            if (!SEGMENT_URI.matcher(segment.uri()).matches()) {
                violations.add("segment URI is not a relative segments/*.ts name: " + segment.uri());
                continue;
            }
            Path file = hlsDirectory.resolve(segment.uri());
            referenced.add(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0) {
                violations.add("missing or empty segment: " + segment.uri());
            }
        }
        try (Stream<Path> entries = Files.walk(hlsDirectory)) {
            entries.filter(entry -> !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    .filter(entry -> !entry.equals(playlistFile) && !referenced.contains(entry))
                    .forEach(entry -> violations.add("unreferenced file: " + hlsDirectory.relativize(entry)));
        }
        double total = playlist.totalSeconds();
        if (Math.abs(total - mediaDurationSeconds) > DURATION_TOLERANCE_SECONDS) {
            violations.add(String.format(Locale.ROOT, "EXTINF sum %.3f s is not within %.0f s of the media duration %.3f s",
                    total, DURATION_TOLERANCE_SECONDS, mediaDurationSeconds));
        }
        return violations;
    }

    /**
     * Every segment but the last lasts the target within the tolerance, none is longer, and
     * #EXT-X-TARGETDURATION covers each rounded #EXTINF (RFC 8216 4.3.3.1).
     */
    public static List<String> segmentDurationViolations(HlsPlaylist playlist, int segmentSeconds, double toleranceSeconds) {
        List<String> violations = new ArrayList<>();
        List<HlsPlaylist.Segment> segments = playlist.segments();
        for (int i = 0; i < segments.size(); i++) {
            double duration = segments.get(i).durationSeconds();
            boolean last = i == segments.size() - 1;
            if (duration > segmentSeconds + toleranceSeconds || (!last && duration < segmentSeconds - toleranceSeconds)) {
                violations.add(String.format(Locale.ROOT, "segment %d lasts %.3f s, target %d s", i, duration, segmentSeconds));
            }
            if (playlist.targetDurationSeconds() != null && Math.round(duration) > playlist.targetDurationSeconds()) {
                violations.add("segment " + i + " exceeds #EXT-X-TARGETDURATION");
            }
        }
        if (playlist.targetDurationSeconds() == null) violations.add("missing #EXT-X-TARGETDURATION");
        return violations;
    }
}
