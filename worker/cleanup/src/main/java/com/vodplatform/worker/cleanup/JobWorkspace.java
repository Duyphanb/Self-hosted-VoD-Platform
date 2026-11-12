package com.vodplatform.worker.cleanup;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Job directories live directly below one dedicated, physically resolved worker root. */
public final class JobWorkspace {
    private static final String PREFIX = "job-";
    private static final String MARKER = ".vod-worker-job";
    private final Path root;
    private final Object fileKey;

    public JobWorkspace(Path root) throws IOException {
        Path systemTemp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (root == null || !root.isAbsolute() || !root.equals(root.normalize()) || root.getParent() == null
                || root.toString().contains("%") || root.equals(systemTemp)
                || root.toString().equals("/tmp") || root.toString().equals("/var/tmp")) {
            throw unsafeDirectory();
        }
        try {
            requirePhysicalDirectory(root.getParent());
            if (Files.notExists(root, NOFOLLOW_LINKS)) Files.createDirectory(root);
            requirePhysicalDirectory(root);
            if (!Files.isWritable(root)) throw unsafeDirectory();
            this.fileKey = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey();
            this.root = root;
        } catch (IOException | SecurityException ignored) {
            throw unsafeDirectory();
        }
    }

    public Path root() {
        return root;
    }

    public Path createJobDirectory() throws IOException {
        requireRoot();
        Path job = Files.createTempDirectory(root, PREFIX);
        requireRoot();
        requirePhysicalDirectory(job);
        Files.createFile(job.resolve(MARKER));
        return job;
    }

    public void removeJobDirectory(Path job) throws IOException {
        if (job == null || !root.equals(job.getParent()) || !job.equals(job.normalize()) || !jobName(job)) {
            throw new IOException("Worker job directory is outside the owned workspace");
        }
        try (var children = openRoot()) {
            removeDirectChild(children, job);
        }
    }

    /** Returns logical bytes in removed regular files. No links or unrelated entries are swept. */
    public long sweep() throws IOException {
        try (var children = openRoot()) {
            long bytes = 0;
            for (Path child : children) {
                if (jobName(child)) bytes = Math.addExact(bytes, removeDirectChild(children, child));
            }
            return bytes;
        }
    }

    private DirectoryStream<Path> openRoot() throws IOException {
        requireRoot();
        var children = Files.newDirectoryStream(root);
        try {
            if (children instanceof SecureDirectoryStream<Path> secure) {
                var attributes = secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
                if (!Objects.equals(fileKey, attributes.fileKey())) throw unsafeDirectory();
            } else {
                requireRoot();
            }
            return children;
        } catch (IOException | RuntimeException failure) {
            try {
                children.close();
            } catch (IOException ignored) {
                // Keep the validation failure.
            }
            throw failure;
        }
    }

    private long removeDirectChild(DirectoryStream<Path> children, Path child) throws IOException {
        // Read the direct entry without following a symlink. Only actual directories are jobs.
        if (children instanceof SecureDirectoryStream<Path> secure) {
            Path name = child.getFileName();
            BasicFileAttributes attributes;
            try {
                attributes = relativeAttributes(secure, name);
            } catch (NoSuchFileException ignored) {
                return 0;
            }
            return attributes.isDirectory() ? removeSecureDirectory(secure, name, true) : 0;
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(child, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException ignored) {
            return 0;
        }
        return attributes.isDirectory()
                && Files.isRegularFile(child.resolve(MARKER), NOFOLLOW_LINKS) ? removeCheckedDirectory(child) : 0;
    }

    private static long removeSecureDirectory(SecureDirectoryStream<Path> parent, Path name, boolean direct)
            throws IOException {
        long bytes = 0;
        try (var directory = parent.newDirectoryStream(name, NOFOLLOW_LINKS)) {
            if (direct) {
                try {
                    if (!relativeAttributes(directory, name.getFileSystem().getPath(MARKER)).isRegularFile()) return 0;
                } catch (NoSuchFileException ignored) {
                    return 0;
                }
            }
            for (Path entry : directory) {
                Path child = entry.getFileName();
                if (direct && child.toString().equals(MARKER)) continue;
                var attributes = relativeAttributes(directory, child);
                if (attributes.isDirectory()) {
                    bytes = Math.addExact(bytes, removeSecureDirectory(directory, child, false));
                } else {
                    directory.deleteFile(child);
                    if (attributes.isRegularFile()) bytes = Math.addExact(bytes, attributes.size());
                }
            }
            // Leave proof of ownership until the last file is removed, even if cleanup is interrupted.
            if (direct) directory.deleteFile(name.getFileSystem().getPath(MARKER));
        }
        parent.deleteDirectory(name);
        return bytes;
    }

    private static BasicFileAttributes relativeAttributes(SecureDirectoryStream<Path> directory, Path name)
            throws IOException {
        return directory.getFileAttributeView(name, BasicFileAttributeView.class, NOFOLLOW_LINKS).readAttributes();
    }

    // Providers without SecureDirectoryStream (including Windows) walk without FOLLOW_LINKS
    // and reject aliased ancestors/reparse directories before deletion.
    private long removeCheckedDirectory(Path job) throws IOException {
        long[] bytes = {0};
        Files.walkFileTree(job, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                requirePhysicalDirectory(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                requireRoot();
                requirePhysicalDirectory(file.getParent());
                if (file.equals(job.resolve(MARKER))) return FileVisitResult.CONTINUE;
                Files.delete(file);
                if (attributes.isRegularFile()) bytes[0] = Math.addExact(bytes[0], attributes.size());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                requireRoot();
                requirePhysicalDirectory(directory);
                if (directory.equals(job)) Files.delete(directory.resolve(MARKER));
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes[0];
    }

    private void requireRoot() throws IOException {
        requirePhysicalDirectory(root);
        if (!Objects.equals(fileKey, Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey())) {
            throw unsafeDirectory();
        }
    }

    private static boolean jobName(Path entry) {
        String name = entry.getFileName().toString();
        return name.startsWith(PREFIX) && name.length() > PREFIX.length();
    }

    private static void requirePhysicalDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || !directory.equals(directory.toRealPath())) {
            throw unsafeDirectory();
        }
    }

    private static IOException unsafeDirectory() {
        return new IOException("Worker temporary directory must be a dedicated writable absolute directory without links");
    }
}
