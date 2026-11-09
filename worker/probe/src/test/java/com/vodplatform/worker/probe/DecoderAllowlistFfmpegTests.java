package com.vodplatform.worker.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.worker.testsupport.MediaTools;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the ADR-010 D26 decoder names against the ADR-010 D25 binary with the production FFprobe command: each
 * family's sample probes with {@link AllowedDecoder#codecWhitelist()}, and fails without its own entry, so every
 * name is both present in the binary and the decoder FFmpeg actually opens for that family.
 */
@Tag(MediaTools.FFMPEG_TAG)
class DecoderAllowlistFfmpegTests {
    private static final ProbeSettings SETTINGS = new ProbeSettings("ffprobe", 30, 10_800, 1920, 1080, 8);
    private static final String VIDEO = "testsrc2=size=64x64:rate=10:duration=1";
    private static final String TONE = "sine=frequency=440:sample_rate=48000:duration=1";
    @TempDir
    static Path directory;
    private static MediaTools tools;

    @BeforeAll
    static void tools() {
        tools = MediaTools.fromEnvironment();
    }

    @Test
    void everyAllowlistedDecoderExistsInTheD25Binary() {
        MediaTools.Result result = tools.ffmpeg(List.of("-hide_banner", "-decoders"));

        assertThat(result.exitCode()).isZero();
        // Lines after the legend look like " V....D libdav1d             dav1d AV1 decoder by VideoLAN (codec av1)".
        Set<String> decoders = result.stdout().lines().map(String::strip).filter(line -> line.matches("[VAS][A-Z.]{5} \\S+.*"))
                .map(line -> line.split("\\s+")[1]).collect(Collectors.toSet());
        assertThat(decoders).hasSizeGreaterThan(100)
                .containsAll(Arrays.stream(AllowedDecoder.values()).map(AllowedDecoder::decoder).toList());
    }

    @ParameterizedTest
    @EnumSource(AllowedDecoder.class)
    void eachFamilyProbesWithTheAllowlistAndNeedsItsOwnEntry(AllowedDecoder family) {
        Path sample = sample(family);
        TrustedDemuxer demuxer = TrustedDemuxer.forKeyExtension(sample.toString().substring(sample.toString().lastIndexOf('.') + 1));
        List<String> arguments = FfprobeCommand.arguments(SETTINGS, demuxer, sample);

        MediaTools.Result allowed = tools.ffprobe(arguments.subList(1, arguments.size()));

        assertThat(allowed.exitCode()).as(allowed.stderr()).isZero();
        JsonNode stream = JsonMapper.builder().build().readTree(allowed.stdout()).get("streams").get(0);
        assertThat(stream.get("codec_type").asString()).isEqualTo(family.type().ffprobeName());
        assertThat(stream.get("codec_name").asString()).isEqualTo(family.codec());

        List<String> without = new ArrayList<>(arguments.subList(1, arguments.size()));
        without.set(without.indexOf("-codec_whitelist") + 1, Arrays.stream(AllowedDecoder.values()).filter(other -> other != family)
                .map(AllowedDecoder::decoder).collect(Collectors.joining(",")));
        MediaTools.Result rejected = tools.ffprobe(without);

        assertThat(rejected.exitCode()).isNotZero();
        assertThat(rejected.stderr()).contains("Codec (" + family.decoder() + ") not on whitelist");
    }

    /** A one-stream sample of the family, encoded by the D25 binary's own encoders. */
    private static Path sample(AllowedDecoder family) {
        String name = family.name().toLowerCase();
        List<String> arguments = new ArrayList<>(List.of("-nostdin", "-hide_banner", "-loglevel", "error", "-y"));
        Path target;
        switch (family.type()) {
            case VIDEO -> {
                target = directory.resolve(name + ".mkv");
                arguments.addAll(List.of("-f", "lavfi", "-i", VIDEO));
                arguments.addAll(switch (family) {
                    case H264 -> List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p");
                    case HEVC -> List.of("-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=error");
                    case VP8 -> List.of("-c:v", "libvpx", "-b:v", "100k");
                    case VP9 -> List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "100k");
                    case AV1 -> List.of("-c:v", "libaom-av1", "-usage", "realtime", "-cpu-used", "8", "-b:v", "100k");
                    case MPEG4_PART2 -> List.of("-c:v", "mpeg4");
                    default -> throw new IllegalArgumentException(family.name());
                });
            }
            case AUDIO -> {
                target = directory.resolve(name + ".mkv");
                arguments.addAll(List.of("-f", "lavfi", "-i", TONE, "-c:a", switch (family) {
                    case MP3 -> "libmp3lame";
                    case OPUS -> "libopus";
                    case VORBIS -> "libvorbis";
                    default -> family.decoder();
                }));
            }
            case SUBTITLE -> {
                // A text cue converted to the family's format; mov_text exists only in MP4.
                target = directory.resolve(name + (family == AllowedDecoder.MOV_TEXT ? ".mp4" : ".mkv"));
                arguments.addAll(List.of("-f", "srt", "-i", "file:" + cue(), "-c:s", switch (family) {
                    case SUBRIP -> "subrip";
                    case ASS -> "ass";
                    default -> family.decoder();
                }));
            }
            default -> throw new IllegalArgumentException(family.name());
        }
        arguments.add(target.toString());
        MediaTools.Result result = tools.ffmpeg(arguments);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        return target;
    }

    private static Path cue() {
        Path cue = directory.resolve("cue.srt");
        try {
            if (!Files.exists(cue)) Files.writeString(cue, "1\n00:00:00,000 --> 00:00:00,500\nHello\n\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cue;
    }
}
