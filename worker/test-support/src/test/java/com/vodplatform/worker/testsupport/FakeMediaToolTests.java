package com.vodplatform.worker.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FakeMediaToolTests {
    @TempDir
    Path directory;

    @Test
    void exitModeWritesBothStreamsAndExitsWithTheCode() throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(FakeMediaTool.command("exit", "3", "out", "err"));
        // A JVM announces JAVA_TOOL_OPTIONS on stderr; the worker's runner never passes it on.
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        Process process = builder.start();

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isEqualTo(3);
        assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("out");
        assertThat(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("err");
    }

    @Test
    void spawnModeRecordsItselfAndALiveChild() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(FakeMediaTool.command("spawn-and-sleep", directory.toString()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            List<Long> pids = FakeMediaTool.awaitPids(directory, Duration.ofSeconds(30));

            assertThat(pids).containsExactly(process.pid(), pids.get(1));
            assertThat(ProcessHandle.of(pids.get(1))).hasValueSatisfying(child -> {
                assertThat(child.isAlive()).isTrue();
                assertThat(child.parent().map(ProcessHandle::pid)).contains(process.pid());
            });
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly().waitFor();
        }
    }
}
