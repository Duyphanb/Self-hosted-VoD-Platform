package com.vodplatform.worker.transcode;

import static com.vodplatform.worker.probe.WorkerFailureCode.ENCODE_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.vodplatform.worker.probe.WorkerFailureException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The ADR-010 D27 checks on hand-written output; HlsTranscodeFfmpegTests runs them on real FFmpeg output. */
class HlsOutputValidatorTests {
    private static final Duration MEDIA = Duration.ofMillis(13_000);
    /** What FFmpeg 7.1 writes for the 13 s corpus at 6 s segments. */
    private static final String FFMPEG_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXTINF:6.000000,
            segments/seg_00000.ts
            #EXTINF:6.000000,
            segments/seg_00001.ts
            #EXTINF:1.000000,
            segments/seg_00002.ts
            #EXT-X-ENDLIST
            """;
    @TempDir
    Path job;
    private Path hls;

    @BeforeEach
    void output() throws IOException {
        hls = Files.createDirectories(job.resolve("hls").resolve("segments")).getParent();
        for (int i = 0; i < 3; i++) segment("seg_0000" + i + ".ts");
    }

    @Test
    void ffmpegOutputPassesAndIsReturnedInPlaylistOrder() throws IOException {
        playlist(FFMPEG_PLAYLIST);

        HlsOutput output = HlsOutputValidator.validate(hls, MEDIA);

        assertThat(output.directory()).isEqualTo(hls);
        assertThat(output.playlist()).isEqualTo(hls.resolve("master.m3u8"));
        assertThat(output.segments()).containsExactly(hls.resolve("segments/seg_00000.ts"), hls.resolve("segments/seg_00001.ts"),
                hls.resolve("segments/seg_00002.ts"));
        assertThat(output.playlistLength()).isEqualTo(Duration.ofSeconds(13));
    }

    @Test
    void theExtinfSumMayDifferFromTheMediaDurationByOneSecondAtMost() throws IOException {
        playlist(FFMPEG_PLAYLIST);

        assertThat(HlsOutputValidator.validate(hls, Duration.ofMillis(12_000)).segments()).hasSize(3);
        assertThat(HlsOutputValidator.validate(hls, Duration.ofMillis(14_000)).segments()).hasSize(3);
        assertInvalid(() -> HlsOutputValidator.validate(hls, Duration.ofNanos(11_999_999_999L)), "within 1 second");
        assertInvalid(() -> HlsOutputValidator.validate(hls, Duration.ofNanos(14_000_000_001L)), "within 1 second");
    }

    @Test
    void aTruncatedEncodeThatStillEndsTheListFailsOnItsLength() throws IOException {
        // FFmpeg exits 0 on a truncated faststart input and writes a shorter, well-formed VOD playlist (ADR-010 D27).
        Files.delete(hls.resolve("segments/seg_00001.ts"));
        Files.delete(hls.resolve("segments/seg_00002.ts"));
        playlist("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:3.900000,\nsegments/seg_00000.ts\n#EXT-X-ENDLIST\n");

        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "within 1 second");
    }

    @Test
    void thePlaylistMustBeAVodListThatEnds() throws IOException {
        playlist(FFMPEG_PLAYLIST.replace("#EXT-X-PLAYLIST-TYPE:VOD\n", ""));
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "not a VOD playlist");
        playlist(FFMPEG_PLAYLIST.replace("TYPE:VOD", "TYPE:EVENT"));
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "not a VOD playlist");
        playlist(FFMPEG_PLAYLIST.replace("#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-PLAYLIST-TYPE:EVENT"));
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "not a VOD playlist");
        playlist(FFMPEG_PLAYLIST.replace("#EXT-X-ENDLIST\n", ""));
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "#EXT-X-ENDLIST");
        playlist(FFMPEG_PLAYLIST.replace("#EXT-X-ENDLIST\n", "") + "#EXT-X-ENDLIST\nsegments/seg_00002.ts\n");
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "EXTINF");
    }

    @Test
    void thePlaylistMustListAtLeastOneSegment() throws IOException {
        clearSegments();
        playlist("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-ENDLIST\n");

        assertInvalid(() -> HlsOutputValidator.validate(hls, Duration.ofMillis(500)), "no segment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/segments/seg_00000.ts", "segments/../segments/seg_00000.ts", "../hls/segments/seg_00000.ts",
            "seg_00000.ts", "segments/sub/seg_00000.ts", "segments/seg_00000.ts?x=1", "http://example.invalid/seg_00000.ts",
            "file:segments/seg_00000.ts", "segments/seg 00000.ts", "segments/seg_00000.m4s", "segments/seg_00000.TS",
            "segments\\seg_00000.ts", "segments/.ts"})
    void everyUriMustBeARelativeSegmentsTsName(String uri) throws IOException {
        playlist(FFMPEG_PLAYLIST.replace("segments/seg_00000.ts", uri));

        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "segments/*.ts");
    }

    @Test
    void everySegmentMustExistOnceAndBeNonEmpty() throws IOException {
        playlist(FFMPEG_PLAYLIST);
        Files.write(hls.resolve("segments/seg_00001.ts"), new byte[0]);
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "missing or empty");

        segment("seg_00001.ts");
        Files.delete(hls.resolve("segments/seg_00002.ts"));
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "missing or empty");

        segment("seg_00002.ts");
        Files.delete(hls.resolve("segments/seg_00000.ts"));
        Files.createDirectory(hls.resolve("segments/seg_00000.ts"));
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "missing or empty");

        Files.delete(hls.resolve("segments/seg_00000.ts"));
        segment("seg_00000.ts");
        playlist(FFMPEG_PLAYLIST.replace("seg_00001", "seg_00000"));
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "twice");
    }

    @Test
    void noUnreferencedFileOrDirectoryMayBePresent() throws IOException {
        playlist(FFMPEG_PLAYLIST);
        for (Path extra : List.of(hls.resolve("segments/seg_00003.ts"), hls.resolve("master.m3u8.tmp"), hls.resolve("extra.ts"))) {
            Files.writeString(extra, "x");
            assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "unreferenced");
            Files.delete(extra);
        }
        Path folder = Files.createDirectory(hls.resolve("segments/nested"));
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "unreferenced");
        Files.delete(folder);
        assertThat(HlsOutputValidator.validate(hls, MEDIA).segments()).hasSize(3);
    }

    @Test
    void linksAreNeitherSegmentsNorAcceptedExtras() throws IOException {
        playlist(FFMPEG_PLAYLIST);
        Path outside = Files.writeString(job.resolve("outside.ts"), "outside the output");
        Path segment = hls.resolve("segments/seg_00002.ts");
        Files.delete(segment);
        assumeTrue(symbolicLink(segment, outside), "this file system cannot create symbolic links");

        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "missing or empty");
        Files.delete(segment);
        segment("seg_00002.ts");
        symbolicLink(hls.resolve("segments/seg_00003.ts"), outside);
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "unreferenced");
    }

    @Test
    void theOutputDirectoriesAndThePlaylistMustBeReal() throws IOException {
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "playlist is missing");
        Files.createDirectory(hls.resolve("master.m3u8"));
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "playlist is missing");
        assertInvalid(() -> HlsOutputValidator.validate(job.resolve("absent"), MEDIA), "directories are missing");
        Files.delete(hls.resolve("master.m3u8"));
        clearSegments();
        Files.delete(hls.resolve("segments"));
        playlist(FFMPEG_PLAYLIST);
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "directories are missing");
    }

    @Test
    void malformedPlaylistsFail() throws IOException {
        for (String text : List.of("", "#EXT-X-VERSION:6\n" + FFMPEG_PLAYLIST, "﻿" + FFMPEG_PLAYLIST,
                FFMPEG_PLAYLIST.replace("#EXTINF:6.000000,\nsegments/seg_00000.ts", "segments/seg_00000.ts"),
                FFMPEG_PLAYLIST.replace("segments/seg_00000.ts\n", ""),
                FFMPEG_PLAYLIST.replace("#EXT-X-ENDLIST\n", "#EXTINF:1.0,\n#EXT-X-ENDLIST\n"),
                FFMPEG_PLAYLIST.replace("#EXTINF:6.000000,\nsegments/seg_00000.ts", "#EXTINF:,\nsegments/seg_00000.ts"),
                FFMPEG_PLAYLIST.replace("#EXTINF:6.000000,", "#EXTINF:-6.0,"),
                FFMPEG_PLAYLIST.replace("#EXTINF:6.000000,", "#EXTINF:NaN,"),
                FFMPEG_PLAYLIST.replace("#EXTINF:6.000000,", "#EXTINF:1e1,"))) {
            playlist(text);
            assertThatThrownBy(() -> HlsOutputValidator.validate(hls, MEDIA)).as(text)
                    .isInstanceOfSatisfying(WorkerFailureException.class, failure -> assertThat(failure.code()).isEqualTo(ENCODE_FAILED));
        }
        Files.write(hls.resolve("master.m3u8"), new byte[] {'#', 'E', 'X', 'T', 'M', '3', 'U', '\n', (byte) 0xC3, (byte) 0x28});
        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "UTF-8");
    }

    @Test
    void crlfLineEndingsAndATitleAfterTheDurationAreAccepted() throws IOException {
        playlist(FFMPEG_PLAYLIST.replace("#EXTINF:1.000000,", "#EXTINF:1.000000,closing title").replace("\n", "\r\n"));

        assertThat(HlsOutputValidator.validate(hls, MEDIA).playlistLength()).isEqualTo(Duration.ofSeconds(13));
    }

    @ParameterizedTest
    @ValueSource(strings = {"#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"", "#EXT-X-MAP:URI=\"init.mp4\"",
            "#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"k\"", "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"p.ts\"",
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"a\",URI=\"a.m3u8\"", "#EXT-X-STREAM-INF:BANDWIDTH=1",
            "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1,URI=\"i.m3u8\""})
    void tagsReferencingOtherResourcesAreOutsideTheProfile(String tag) throws IOException {
        playlist(FFMPEG_PLAYLIST.replace("#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-INDEPENDENT-SEGMENTS\n" + tag));

        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "outside the segment profile");
    }

    @Test
    void anOversizedPlaylistFailsBeforeItIsRead() throws IOException {
        playlist(FFMPEG_PLAYLIST + "#" + "x".repeat(HlsOutputValidator.MAX_PLAYLIST_BYTES));

        assertInvalid(() -> HlsOutputValidator.validate(hls, MEDIA), "size bound");
    }

    @Test
    void failureMessagesNeverQuoteThePlaylistOrAPath() throws IOException {
        playlist(FFMPEG_PLAYLIST.replace("segments/seg_00000.ts", "segments/secret-name.mp4"));

        assertThatThrownBy(() -> HlsOutputValidator.validate(hls, MEDIA)).hasMessageNotContaining("secret")
                .hasMessageNotContaining(hls.toString());
    }

    private void playlist(String text) throws IOException {
        Files.writeString(hls.resolve("master.m3u8"), text, StandardCharsets.UTF_8);
    }

    private void segment(String name) throws IOException {
        Files.write(hls.resolve("segments").resolve(name), new byte[] {0x47, 0x40, 0x00, 0x10});
    }

    private void clearSegments() throws IOException {
        try (var entries = Files.list(hls.resolve("segments"))) {
            for (Path entry : entries.toList()) Files.delete(entry);
        }
    }

    private static boolean symbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | FileSystemException e) {
            return false;
        }
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(WorkerFailureException.class, failure -> {
            assertThat(failure.code()).isEqualTo(ENCODE_FAILED);
            assertThat(failure.getMessage()).contains(reason);
        });
    }
}
