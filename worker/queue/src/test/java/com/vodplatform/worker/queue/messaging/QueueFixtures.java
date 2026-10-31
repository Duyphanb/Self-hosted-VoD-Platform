package com.vodplatform.worker.queue.messaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared repository fixtures are mandatory test inputs, never a reason to skip. */
final class QueueFixtures {
    private QueueFixtures() { }

    static Path repositoryFile(String relativePath) {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Required repository fixture is missing: " + relativePath);
    }

    static byte[] golden() throws IOException {
        return Files.readAllBytes(repositoryFile("docs/testing/fixtures/encoding-job-v1.json"));
    }
}
