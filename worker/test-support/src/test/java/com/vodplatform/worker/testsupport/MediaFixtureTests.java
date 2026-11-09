package com.vodplatform.worker.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaFixtureTests {

    @Test
    void everyFixtureHasAUniqueNameAndATrustedKeyExtension() {
        List<String> names = Arrays.stream(MediaFixture.values()).map(MediaFixture::fileName).toList();

        assertThat(names).doesNotHaveDuplicates().allMatch(name -> name.matches("[a-z0-9-]+\\.(mp4|mov|mkv|webm)"));
        assertThat(MediaFixture.VP9_OPUS_WEBM.demuxer()).isEqualTo(MediaFixture.Demuxer.MATROSKA);
        assertThat(MediaFixture.H264_AAC_MP4.demuxer()).isEqualTo(MediaFixture.Demuxer.MOV);
        assertThat(Arrays.stream(MediaFixture.values()).filter(MediaFixture::valid)).hasSize(20);
    }

    @Test
    void demuxerTableMatchesAdr010AndRejectsOtherExtensions() {
        assertThat(MediaFixture.Demuxer.forKeyExtension("mp4").ffmpegName()).isEqualTo("mov");
        assertThat(MediaFixture.Demuxer.forKeyExtension("mov").ffmpegName()).isEqualTo("mov");
        assertThat(MediaFixture.Demuxer.forKeyExtension("mkv").ffmpegName()).isEqualTo("matroska");
        assertThat(MediaFixture.Demuxer.forKeyExtension("webm").ffmpegName()).isEqualTo("matroska");
        for (String extension : List.of("m3u8", "ffconcat", "avi", "MP4", "")) {
            assertThatThrownBy(() -> MediaFixture.Demuxer.forKeyExtension(extension)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void toolsFailInsteadOfSkippingWhenTheImageBinaryIsNotConfigured() {
        assertThatThrownBy(() -> new MediaTools(null, "ffprobe"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FFMPEG_PATH");
        assertThatThrownBy(() -> new MediaTools("ffmpeg", " "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FFPROBE_PATH");
    }
}
