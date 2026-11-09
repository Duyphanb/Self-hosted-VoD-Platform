package com.vodplatform.worker.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vodplatform.worker.probe.MediaMetadata;
import com.vodplatform.worker.probe.TrustedDemuxer;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FfmpegCommandTests {
    /** The ADR-010 D27/D28 policy values; the two deadline values are only test values (#124 measures them). */
    static final TranscodeSettings POLICY = new TranscodeSettings("ffmpeg", 60, new BigDecimal("3"), 6, 720, 4000, 1, 8);
    private static final String WHITELIST = "h264,hevc,vp8,vp9,libdav1d,mpeg4,aac,mp3float,opus,vorbis,ac3,eac3,flac,alac,"
            + "pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,mov_text,srt,ssa,webvtt";
    @TempDir
    Path directory;

    @Test
    void movInputWithDisplayMatrixHasTheExactIssueArgumentList() {
        Path job = directory.resolve("job-1");
        Path input = job.resolve("raw.mp4");
        Path hls = job.resolve("hls");

        assertThat(FfmpegCommand.arguments(POLICY, TrustedDemuxer.MOV, input, hls, landscape(180))).containsExactly(
                "ffmpeg", "-nostdin", "-hide_banner",
                "-xerror", "-nostats", "-loglevel", "error", "-progress", "pipe:1", "-stats_period", "1",
                "-protocol_whitelist", "file",
                "-codec_whitelist", WHITELIST,
                "-max_streams", "8",
                "-f", "mov",
                "-i", "file:" + input,
                "-map", "0:V:0", "-map", "0:a:0?", "-map_metadata", "-1", "-map_chapters", "-1",
                "-vf", "scale=w=min(1280\\,iw):h=min(720\\,ih):force_original_aspect_ratio=decrease:force_divisible_by=2,"
                        + "setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709",
                "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "23",
                "-maxrate:v", "4000k", "-bufsize:v", "8000k",
                "-fpsmax", "60", "-force_key_frames", "expr:gte(t,n_forced*6)",
                "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
                "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "128k", "-ac", "2", "-ar", "48000",
                "-threads", "1", "-filter_threads", "1",
                "-f", "hls", "-hls_time", "6", "-hls_playlist_type", "vod", "-hls_list_size", "0",
                "-hls_segment_type", "mpegts", "-hls_flags", "independent_segments", "-hls_base_url", "segments/",
                "-hls_segment_filename", hls.resolve("segments").resolve("seg_%05d.ts").toString(),
                hls.resolve("master.m3u8").toString());
    }

    @Test
    void matroskaPortraitInputWithLegacyRotateTagHasTheExactIssueArgumentList() {
        // A legacy rotate=90 tag turns a 1920x1080 frame into a 1080x1920 display.
        String ffmpeg = directory.resolve("bin").resolve("ffmpeg").toString();
        TranscodeSettings settings = new TranscodeSettings(ffmpeg, 60, new BigDecimal("3"), 4, 480, 2500, 2, 3);
        Path input = directory.resolve("job-2").resolve("raw.mkv");
        Path hls = directory.resolve("job-2").resolve("hls");
        MediaMetadata portrait = new MediaMetadata(Duration.ofSeconds(13), 1080, 1920, 270, true, "vp9", null);

        assertThat(FfmpegCommand.arguments(settings, TrustedDemuxer.MATROSKA, input, hls, portrait)).containsExactly(
                ffmpeg, "-nostdin", "-hide_banner",
                "-xerror", "-nostats", "-loglevel", "error", "-progress", "pipe:1", "-stats_period", "1",
                "-protocol_whitelist", "file",
                "-codec_whitelist", WHITELIST,
                "-max_streams", "3",
                "-display_rotation:V:0", "270",
                "-f", "matroska",
                "-i", "file:" + input,
                "-map", "0:V:0", "-map", "0:a:0?", "-map_metadata", "-1", "-map_chapters", "-1",
                "-vf", "scale=w=min(480\\,iw):h=min(852\\,ih):force_original_aspect_ratio=decrease:force_divisible_by=2,"
                        + "setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709",
                "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "23",
                "-maxrate:v", "2500k", "-bufsize:v", "5000k",
                "-fpsmax", "60", "-force_key_frames", "expr:gte(t,n_forced*4)",
                "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
                "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "128k", "-ac", "2", "-ar", "48000",
                "-threads", "2", "-filter_threads", "2",
                "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod", "-hls_list_size", "0",
                "-hls_segment_type", "mpegts", "-hls_flags", "independent_segments", "-hls_base_url", "segments/",
                "-hls_segment_filename", hls.resolve("segments").resolve("seg_%05d.ts").toString(),
                hls.resolve("master.m3u8").toString());
    }

    @ParameterizedTest
    @CsvSource({"640, 360, 1280, 720", "360, 640, 720, 1280", "1920, 1080, 1280, 720", "1080, 1920, 720, 1280",
            "720, 720, 1280, 720", "641, 359, 1280, 720"})
    void theBoxFollowsTheDisplayOrientation(int width, int height, int boxWidth, int boxHeight) {
        MediaMetadata metadata = new MediaMetadata(Duration.ofSeconds(5), width, height, 0, false, "h264", null);

        assertThat(FfmpegCommand.videoFilter(POLICY, metadata))
                .startsWith("scale=w=min(" + boxWidth + "\\,iw):h=min(" + boxHeight + "\\,ih):");
    }

    @Test
    void inputWithoutALegacyRotateTagUsesNativeAutorotation() {
        for (int rotation : new int[] {0, 90, 180, 270}) {
            List<String> arguments = FfmpegCommand.arguments(POLICY, TrustedDemuxer.MOV, directory.resolve("raw.mp4"),
                    directory.resolve("hls"), landscape(rotation));

            assertThat(arguments).doesNotContain("-display_rotation:V:0", "-noautorotate", "-autorotate");
        }
    }

    @Test
    void inputOptionsPrecedeTheInputAndNoStdinOrShellPathIsUsed() {
        List<String> arguments = FfmpegCommand.arguments(POLICY, TrustedDemuxer.MOV, directory.resolve("raw.mp4"),
                directory.resolve("hls"), landscape(0));
        int input = arguments.indexOf("-i");

        for (String option : List.of("-protocol_whitelist", "-codec_whitelist", "-max_streams", "-f")) {
            assertThat(arguments.indexOf(option)).as(option).isBetween(1, input - 1);
        }
        assertThat(arguments.lastIndexOf("-f")).isGreaterThan(input);
        assertThat(arguments).doesNotContain("-", "pipe:0", "-y", "-level", "-level:v", "copy");
        assertThat(arguments).filteredOn(argument -> argument.startsWith("-c:a")).containsExactly("-c:a");
    }

    @Test
    void relativePathsAndPercentSignsAreRejected() {
        Path hls = directory.resolve("hls");

        assertThatThrownBy(() -> FfmpegCommand.arguments(POLICY, TrustedDemuxer.MOV, Path.of("raw.mp4"), hls, landscape(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FfmpegCommand.arguments(POLICY, TrustedDemuxer.MOV, directory.resolve("raw.mp4"),
                Path.of("hls"), landscape(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FfmpegCommand.arguments(POLICY, TrustedDemuxer.MOV, directory.resolve("raw.mp4"),
                directory.resolve("job-%d").resolve("hls"), landscape(0))).isInstanceOf(IllegalArgumentException.class);
    }

    private static MediaMetadata landscape(int rotation) {
        boolean quarterTurn = rotation == 90 || rotation == 270;
        return new MediaMetadata(Duration.ofSeconds(13), quarterTurn ? 360 : 640, quarterTurn ? 640 : 360, rotation,
                false, "h264", 1000);
    }
}
