package com.vodplatform.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

class UploadSpoolTest {
    @TempDir Path temp;

    @Test
    void createsAndSweepsOnlyMatchingRegularFilesNonRecursively() throws Exception {
        Path dir = temp.resolve("dedicated");
        Files.createDirectories(dir);
        Path stale = Files.writeString(dir.resolve("upload_stale.tmp"), "old");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(1));
        Path future = Files.writeString(dir.resolve("upload_future.tmp"), "new");
        Files.setLastModifiedTime(future, FileTime.fromMillis(System.currentTimeMillis() + 60000));
        Path other = Files.writeString(dir.resolve("other.tmp"), "keep");
        Path nested = Files.createDirectory(dir.resolve("upload_dir.tmp"));
        Files.writeString(nested.resolve("upload_nested.tmp"), "keep");
        UploadConfiguration.prepareSpool(dir.toString());
        assertThat(stale).doesNotExist();
        assertThat(future).exists();
        assertThat(other).exists();
        assertThat(nested.resolve("upload_nested.tmp")).exists();
    }

    @Test
    void rejectsUnsafePaths() {
        for (String path :
                new String[] {
                    "",
                    "relative",
                    temp.getRoot().toString(),
                    System.getProperty("java.io.tmpdir"),
                    System.getProperty("user.home"),
                    temp.resolve("x/../y").toString()
                }) {
            assertThatThrownBy(() -> UploadConfiguration.prepareSpool(path))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void rejectsSymlinkAncestorsAndNeverSweepsSymlinkTargets() throws Exception {
        Path target = Files.createDirectory(temp.resolve("target"));
        Path link = Files.createSymbolicLink(temp.resolve("link"), target);
        assertThatThrownBy(
                        () -> UploadConfiguration.prepareSpool(link.resolve("nested").toString()))
                .isInstanceOf(IllegalArgumentException.class);
        Path outside = Files.writeString(temp.resolve("outside"), "keep");
        Path fileLink = Files.createSymbolicLink(target.resolve("upload_link.tmp"), outside);
        UploadConfiguration.prepareSpool(target.toString());
        assertThat(fileLink).exists();
        assertThat(outside).hasContent("keep");
    }
}
