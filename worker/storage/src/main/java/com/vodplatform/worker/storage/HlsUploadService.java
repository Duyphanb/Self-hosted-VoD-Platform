package com.vodplatform.worker.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import com.vodplatform.worker.transcode.HlsOutput;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Publishes the validated D27 output to one private attempt prefix. */
public final class HlsUploadService {
    private static final String PLAYLIST = "master.m3u8";
    private static final String SEGMENTS = "segments";
    private static final Pattern SEGMENT_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.ts");

    private final WorkerObjectStorage storage;
    private final String hlsBucket;

    HlsUploadService(WorkerObjectStorage storage, String hlsBucket) {
        this.storage = storage;
        this.hlsBucket = hlsBucket;
    }

    public record UploadedHls(String bucket, String masterObjectKey) {}

    /** The caller persists the returned location only in its conditional READY transaction. */
    public UploadedHls upload(UUID assetId, int attempt, HlsOutput output) throws IOException {
        String prefix = attemptPrefix(assetId, attempt);
        Path directory = output.directory().toAbsolutePath().normalize();
        Path segmentsDirectory = directory.resolve(SEGMENTS);
        Path playlist = output.playlist().toAbsolutePath().normalize();
        try {
            if (!directory.equals(directory.toRealPath()) || !Files.isDirectory(segmentsDirectory, NOFOLLOW_LINKS)
                    || !playlist.equals(directory.resolve(PLAYLIST)) || output.segments().isEmpty()) {
                throw invalidOutput();
            }
            requireNonemptyFile(playlist);
            var segments = new ArrayList<Path>(output.segments().size());
            var names = new HashSet<String>();
            for (Path supplied : output.segments()) {
                Path segment = supplied.toAbsolutePath().normalize();
                if (!segmentsDirectory.equals(segment.getParent())) throw invalidOutput();
                String name = segment.getFileName().toString();
                if (!SEGMENT_NAME.matcher(name).matches() || !names.add(name)) throw invalidOutput();
                requireNonemptyFile(segment);
                segments.add(segment);
            }
            // Publish the playlist only after every referenced segment has been written.
            for (Path segment : segments) {
                put(segment, prefix + SEGMENTS + "/" + segment.getFileName(), "video/mp2t");
            }
            String masterKey = prefix + PLAYLIST;
            put(playlist, masterKey, "application/vnd.apple.mpegurl");
            return new UploadedHls(hlsBucket, masterKey);
        } catch (IOException ignored) {
            throw new IOException("HLS upload failed");
        }
    }

    public static String attemptPrefix(UUID assetId, int attempt) {
        Objects.requireNonNull(assetId, "assetId");
        if (attempt < 1) throw new IllegalArgumentException("Attempt must be positive");
        return "video-assets/" + assetId + "/attempt-" + attempt + "/";
    }

    private void put(Path file, String key, String contentType) throws IOException {
        try (var channel = Files.newByteChannel(file, READ, NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size == 0) throw invalidOutput();
            storage.putHls(key, Channels.newInputStream(channel), size, contentType);
        }
    }

    private static void requireNonemptyFile(Path file) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() == 0) throw invalidOutput();
    }

    private static IOException invalidOutput() {
        return new IOException("Invalid validated HLS output");
    }
}
