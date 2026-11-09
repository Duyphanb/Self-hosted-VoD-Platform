package com.vodplatform.worker.transcode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A validated single-quality VOD HLS output (ADR-010 D27): Issue 5.6 uploads exactly these files under the
 * attempt prefix, the playlist as {@code master.m3u8} and each segment under {@code segments/}.
 *
 * @param directory      the local HLS directory, {@code <job>/hls}
 * @param playlist       {@code <job>/hls/master.m3u8}
 * @param segments       the segment files in playlist order, each {@code <job>/hls/segments/<name>.ts}; no others exist
 * @param playlistLength the sum of the playlist's EXTINF durations
 */
public record HlsOutput(Path directory, Path playlist, List<Path> segments, Duration playlistLength) {
    public HlsOutput {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(playlist, "playlist");
        segments = List.copyOf(segments);
        Objects.requireNonNull(playlistLength, "playlistLength");
    }
}
