package com.vodplatform.worker.cleanup;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobWorkspaceTests {
    @TempDir
    Path fixture;

    @Test
    void sweepsOnlyDirectJobDirectoriesAndCountsRegularFileBytes() throws IOException {
        var workspace = new JobWorkspace(fixture.toRealPath().resolve("workspace"));
        Path job = workspace.createJobDirectory();
        Path output = Files.createDirectory(job.resolve("hls"));
        Files.write(output.resolve("segment.ts"), new byte[7]);
        Files.write(job.resolve("raw.mp4"), new byte[13]);
        Path unrelated = Files.createDirectory(workspace.root().resolve("not-a-job"));
        Path unrelatedJob = Files.createDirectory(unrelated.resolve("job-foreign"));
        Path samePrefixFile = Files.write(workspace.root().resolve("job-file"), new byte[5]);
        Path unmarkedJob = Files.createDirectory(workspace.root().resolve("job-123"));
        Path unownedFile = Files.write(unmarkedJob.resolve("preserve"), new byte[19]);

        assertEquals(20, workspace.sweep());
        assertFalse(Files.exists(job, NOFOLLOW_LINKS));
        assertTrue(Files.exists(unrelatedJob));
        assertTrue(Files.exists(samePrefixFile));
        assertTrue(Files.exists(unownedFile));
        assertEquals(0, workspace.sweep());
    }

    @Test
    void removesAnOwnedJobAndRejectsAnExternalPath() throws IOException {
        var workspace = new JobWorkspace(fixture.toRealPath().resolve("workspace"));
        Path external = Files.createDirectory(fixture.resolve("job-external"));
        Path job = workspace.createJobDirectory();
        assertThrows(IOException.class, () -> workspace.removeJobDirectory(external));
        assertTrue(Files.exists(external));
        workspace.removeJobDirectory(job);
        workspace.removeJobDirectory(job);
        assertFalse(Files.exists(job, NOFOLLOW_LINKS));
    }

    @Test
    void rejectsUnsafeRootsAndDoesNotFollowLinksDuringSweep() throws IOException {
        Path root = fixture.toRealPath();
        assertThrows(IOException.class, () -> new JobWorkspace(Path.of("relative")));
        assertThrows(IOException.class, () -> new JobWorkspace(root.getRoot()));
        assertThrows(IOException.class, () -> new JobWorkspace(root.resolve("percent%")));

        var workspace = new JobWorkspace(root.resolve("workspace"));
        Path target = Files.createDirectory(root.resolve("target"));
        Path targetFile = Files.write(target.resolve("preserve"), new byte[11]);
        Path aliasedRoot = link(root.resolve("alias"), target);
        Path outsideJob = link(workspace.root().resolve("job-link"), target);
        Path job = workspace.createJobDirectory();
        link(job.resolve("nested-link"), target);

        assertThrows(IOException.class, () -> new JobWorkspace(aliasedRoot));
        assertThrows(IOException.class, () -> new JobWorkspace(aliasedRoot.resolve("outside")));
        assertEquals(0, workspace.sweep());
        assertTrue(Files.isSymbolicLink(outsideJob));
        assertTrue(Files.exists(targetFile));
        assertFalse(Files.exists(job, NOFOLLOW_LINKS));
    }

    private static Path link(Path path, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(path, target);
        } catch (IOException | UnsupportedOperationException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable on this host");
            throw unavailable;
        }
    }
}
