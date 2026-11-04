package com.vodplatform.worker.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vodplatform.worker.testsupport.MediaFixture;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The fixed ADR-010 tables: D28 failure codes, the D6 demuxer table and the D26 decoder allowlist. */
class MediaRulesTests {

    @Test
    void failureCodesAreExactlyTheAdr010D28Enum() {
        assertThat(Arrays.stream(WorkerFailureCode.values()).map(Enum::name)).containsExactly(
                "PROBE_FAILED", "PROBE_TIMEOUT", "UNSUPPORTED_MEDIA", "MEDIA_LIMIT_EXCEEDED",
                "ENCODE_FAILED", "ENCODE_TIMEOUT", "INSUFFICIENT_DISK", "STORAGE_ERROR",
                "WORKER_INTERRUPTED", "INTERNAL_ERROR");
    }

    @Test
    void failureExceptionCarriesItsCodeAndSafeMessage() {
        RuntimeException cause = new RuntimeException("tool detail");
        WorkerFailureException failure = new WorkerFailureException(WorkerFailureCode.PROBE_FAILED, "FFprobe failed", cause);

        assertThat(failure.code()).isEqualTo(WorkerFailureCode.PROBE_FAILED);
        assertThat(failure).hasMessage("FFprobe failed").hasCause(cause);
        assertThatThrownBy(() -> new WorkerFailureException(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkerFailureException(WorkerFailureCode.PROBE_FAILED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void trustedKeyExtensionsForceTheAdr010Demuxers() {
        assertThat(TrustedDemuxer.forKeyExtension("mp4")).isEqualTo(TrustedDemuxer.MOV);
        assertThat(TrustedDemuxer.forKeyExtension("mov")).isEqualTo(TrustedDemuxer.MOV);
        assertThat(TrustedDemuxer.forKeyExtension("mkv")).isEqualTo(TrustedDemuxer.MATROSKA);
        assertThat(TrustedDemuxer.forKeyExtension("webm")).isEqualTo(TrustedDemuxer.MATROSKA);
        assertThat(TrustedDemuxer.MOV.ffmpegName()).isEqualTo("mov");
        assertThat(TrustedDemuxer.MATROSKA.ffmpegName()).isEqualTo("matroska");
    }

    @Test
    void anyOtherExtensionIsUnsupportedMedia() {
        for (String extension : Arrays.asList(null, "", "MP4", "Mkv", "m3u8", "ffconcat", "avi", "ts", ".mp4", "mp4 ")) {
            assertThatThrownBy(() -> TrustedDemuxer.forKeyExtension(extension)).as(String.valueOf(extension))
                    .isInstanceOfSatisfying(WorkerFailureException.class,
                            failure -> assertThat(failure.code()).isEqualTo(WorkerFailureCode.UNSUPPORTED_MEDIA));
        }
    }

    @Test
    void demuxerTableAgreesWithTheTestFixtureTable() {
        for (MediaFixture fixture : MediaFixture.values()) {
            String extension = fixture.fileName().substring(fixture.fileName().lastIndexOf('.') + 1);
            assertThat(TrustedDemuxer.forKeyExtension(extension).ffmpegName()).as(fixture.name())
                    .isEqualTo(fixture.demuxer().ffmpegName());
        }
    }

    @Test
    void codecWhitelistNamesTheD25DecoderOfEveryD26Family() {
        // Pinned against the binary by DecoderAllowlistFfmpegTests; where a codec has several decoders FFmpeg
        // opens the first registered one (srt, ssa, mp3float, libdav1d).
        assertThat(AllowedDecoder.codecWhitelist()).isEqualTo("h264,hevc,vp8,vp9,libdav1d,mpeg4,"
                + "aac,mp3float,opus,vorbis,ac3,eac3,flac,alac,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,"
                + "mov_text,srt,ssa,webvtt");
        assertThat(codecs(AllowedDecoder.MediaType.VIDEO)).containsExactly("h264", "hevc", "vp8", "vp9", "av1", "mpeg4");
        assertThat(codecs(AllowedDecoder.MediaType.AUDIO)).containsExactly("aac", "mp3", "opus", "vorbis", "ac3", "eac3",
                "flac", "alac", "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be");
        assertThat(codecs(AllowedDecoder.MediaType.SUBTITLE)).containsExactly("mov_text", "subrip", "ass", "webvtt");
    }

    @Test
    void familiesMatchOnTypeAndCodecName() {
        assertThat(AllowedDecoder.allows("video", "h264")).isTrue();
        assertThat(AllowedDecoder.allows("subtitle", "subrip")).isTrue();
        assertThat(AllowedDecoder.allows("audio", "h264")).isFalse();
        assertThat(AllowedDecoder.allows("video", "mpeg2video")).isFalse();
        assertThat(AllowedDecoder.allows("video", "png")).isFalse();
        assertThat(AllowedDecoder.allows("subtitle", "hdmv_pgs_subtitle")).isFalse();
        assertThat(AllowedDecoder.allows("video", null)).isFalse();
        assertThat(AllowedDecoder.allows(null, "h264")).isFalse();
        assertThat(List.of("video", "audio", "subtitle")).allMatch(AllowedDecoder.MediaType::isDecoded);
        assertThat(Arrays.asList("data", "attachment", null)).noneMatch(AllowedDecoder.MediaType::isDecoded);
    }

    @Test
    void metadataRoundsTheDurationUpToWholeSeconds() {
        assertThat(metadata(Duration.ofSeconds(13)).durationSeconds()).isEqualTo(13);
        assertThat(metadata(Duration.ofSeconds(13, 1)).durationSeconds()).isEqualTo(14);
        assertThat(metadata(Duration.ofMillis(500)).durationSeconds()).isOne();
    }

    @Test
    void metadataRejectsValuesTheRulesNeverProduce() {
        assertThatThrownBy(() -> metadata(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> metadata(Duration.ofSeconds(Integer.MAX_VALUE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaMetadata(null, 1, 1, 0, "h264", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MediaMetadata(Duration.ofSeconds(1), 0, 1, 0, "h264", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaMetadata(Duration.ofSeconds(1), 1, 1, 45, "h264", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaMetadata(Duration.ofSeconds(1), 1, 1, 360, "h264", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaMetadata(Duration.ofSeconds(1), 1, 1, 0, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaMetadata(Duration.ofSeconds(1), 1, 1, 0, "x".repeat(101), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaMetadata(Duration.ofSeconds(1), 1, 1, 0, "h264", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MediaMetadata metadata(Duration duration) {
        return new MediaMetadata(duration, 640, 360, 0, "h264", null);
    }

    private static List<String> codecs(AllowedDecoder.MediaType type) {
        return Arrays.stream(AllowedDecoder.values()).filter(decoder -> decoder.type() == type).map(AllowedDecoder::codec).toList();
    }
}
