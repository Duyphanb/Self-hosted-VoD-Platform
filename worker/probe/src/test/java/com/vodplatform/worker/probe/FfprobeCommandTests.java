package com.vodplatform.worker.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfprobeCommandTests {
    @TempDir
    Path directory;

    @Test
    void movInputHasTheExactRestrictedArgumentList() {
        Path input = directory.resolve("job-1").resolve("raw.mp4");
        ProbeSettings settings = new ProbeSettings("ffprobe", 30, 10_800, 1920, 1080, 8);

        assertThat(FfprobeCommand.arguments(settings, TrustedDemuxer.MOV, input)).containsExactly(
                "ffprobe", "-hide_banner", "-loglevel", "error",
                "-protocol_whitelist", "file",
                "-codec_whitelist", "h264,hevc,vp8,vp9,libdav1d,mpeg4,aac,mp3float,opus,vorbis,ac3,eac3,flac,alac,"
                        + "pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,mov_text,srt,ssa,webvtt",
                "-max_streams", "8",
                "-f", "mov",
                "-show_entries", "format=duration,bit_rate:stream=index,codec_type,codec_name,width,height,duration"
                        + ":stream_disposition=attached_pic:stream_tags=rotate:stream_side_data=side_data_type,rotation",
                "-of", "json",
                "-i", "file:" + input);
    }

    @Test
    void matroskaInputUsesTheConfiguredPathAndStreamCap() {
        String ffprobe = directory.resolve("bin").resolve("ffprobe").toString();
        Path input = directory.resolve("raw.webm");

        List<String> arguments = FfprobeCommand.arguments(new ProbeSettings(ffprobe, 30, 10_800, 1920, 1080, 3),
                TrustedDemuxer.MATROSKA, input);

        assertThat(arguments.getFirst()).isEqualTo(ffprobe);
        assertThat(arguments).containsSequence("-max_streams", "3").containsSequence("-f", "matroska")
                .endsWith("-i", "file:" + input);
    }

    @Test
    void neverPassesFfmpegOnlyOptionsOrARelativeInput() {
        List<String> arguments = FfprobeCommand.arguments(new ProbeSettings("ffprobe", 30, 10_800, 1920, 1080, 8),
                TrustedDemuxer.MOV, directory.resolve("raw.mov"));

        // -nostdin is an FFmpeg option; FFprobe has none, so the runner closes stdin instead (SECURITY.md).
        assertThat(arguments).doesNotContain("-nostdin", "-", "pipe:0");
        assertThat(arguments.indexOf("-protocol_whitelist")).isLessThan(arguments.indexOf("-i"));
        assertThat(arguments.indexOf("-codec_whitelist")).isLessThan(arguments.indexOf("-i"));
        assertThat(arguments.indexOf("-f")).isLessThan(arguments.indexOf("-i"));
        assertThatThrownBy(() -> FfprobeCommand.arguments(new ProbeSettings("ffprobe", 30, 10_800, 1920, 1080, 8),
                TrustedDemuxer.MOV, Path.of("raw.mp4"))).isInstanceOf(IllegalArgumentException.class);
    }
}
