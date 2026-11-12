package com.vodplatform.worker.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.vodplatform.worker.transcode.HlsOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import okhttp3.Dispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HlsUploadServiceTests {
    private static final UUID ASSET = UUID.fromString("ABABABAB-BBBB-CCCC-DDDD-ABCDEFABCDEF");
    private final WorkerObjectStorage storage = mock(WorkerObjectStorage.class);
    private final HlsUploadService uploader = new HlsUploadService(storage, "vod-hls", new HlsWriteGuard(new Dispatcher()));

    @TempDir
    Path outputDir;

    @Test
    void attemptPrefixMatchesTheRunbookSql() {
        assertThat(HlsUploadService.attemptPrefix(ASSET, 1))
                .isEqualTo("video-assets/abababab-bbbb-cccc-dddd-abcdefabcdef/attempt-1/");
        assertThat(HlsUploadService.attemptPrefix(ASSET, 10))
                .isEqualTo("video-assets/abababab-bbbb-cccc-dddd-abcdefabcdef/attempt-10/")
                .doesNotStartWith(HlsUploadService.attemptPrefix(ASSET, 1));
        assertThatThrownBy(() -> HlsUploadService.attemptPrefix(ASSET, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesSegmentsBeforeThePlaylistAndReturnsOnlyItsPrivateLocation() throws IOException {
        Path directory = outputDir.toRealPath();
        Path segments = Files.createDirectory(directory.resolve("segments"));
        Path playlist = Files.writeString(directory.resolve("master.m3u8"), "#EXTM3U");
        Path segment = Files.writeString(segments.resolve("a-b_1.2.ts"), "segment");
        HlsOutput output = new HlsOutput(directory, playlist, List.of(segment), Duration.ofSeconds(1));

        var uploaded = uploader.upload(ASSET, 2, output);

        String prefix = "video-assets/abababab-bbbb-cccc-dddd-abcdefabcdef/attempt-2/";
        var order = inOrder(storage);
        order.verify(storage).putHls(eq(prefix + "segments/a-b_1.2.ts"), any(), eq(7L), eq("video/mp2t"));
        order.verify(storage).putHls(eq(prefix + "master.m3u8"), any(), eq(7L), eq("application/vnd.apple.mpegurl"));
        order.verifyNoMoreInteractions();
        assertThat(uploaded).isEqualTo(new HlsUploadService.UploadedHls("vod-hls", prefix + "master.m3u8"));
    }

    @Test
    void rejectsInvalidSegmentNamesAndPathsBeforeAnyWrite() throws IOException {
        Path directory = outputDir.toRealPath();
        Path segments = Files.createDirectory(directory.resolve("segments"));
        Path playlist = Files.writeString(directory.resolve("master.m3u8"), "#EXTM3U");
        Path valid = Files.writeString(segments.resolve("valid.ts"), "segment");
        Path invalidName = Files.writeString(segments.resolve("bad name.ts"), "segment");
        Path outsideSegments = Files.writeString(directory.resolve("outside.ts"), "segment");
        for (var entries : List.of(List.of(valid, invalidName), List.of(valid, outsideSegments), List.of(valid, valid))) {
            var output = new HlsOutput(directory, playlist, entries, Duration.ofSeconds(1));
            assertThatThrownBy(() -> uploader.upload(ASSET, 1, output))
                    .isInstanceOf(IOException.class).hasMessage("HLS upload failed");
        }
        verifyNoInteractions(storage);
    }
}
