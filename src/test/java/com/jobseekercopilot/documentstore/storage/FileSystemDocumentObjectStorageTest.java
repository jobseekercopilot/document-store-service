package com.jobseekercopilot.documentstore.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemDocumentObjectStorageTest {
    @TempDir
    Path root;

    @Test
    void availabilityCheckFailsWhenTheIsolatedRootDisappears()
            throws Exception {
        var storage = new FileSystemDocumentObjectStorage(root);
        Files.delete(root);

        assertThatThrownBy(storage::checkAvailability)
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void objectSurvivesAdapterRestartAndCanBeDeleted() {
        byte[] content = "recovery-evidence".getBytes(StandardCharsets.UTF_8);
        var first = new FileSystemDocumentObjectStorage(root);
        first.put("documents/a/files/b/v1", content, "application/pdf", ObjectIntegrity.sha256(content));

        var restarted = new FileSystemDocumentObjectStorage(root);
        assertThat(restarted.exists("documents/a/files/b/v1")).isTrue();
        assertThat(restarted.get("documents/a/files/b/v1")).isEqualTo(content);

        restarted.delete("documents/a/files/b/v1");
        assertThat(restarted.exists("documents/a/files/b/v1")).isFalse();
    }

    @Test
    void rejectsTraversalOutsideTheIsolatedRoot() {
        var storage = new FileSystemDocumentObjectStorage(root);

        assertThatThrownBy(() -> storage.put(
                        "../escaped", new byte[] {1}, "application/pdf", "digest"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    void retryIsIdempotentButDifferentBytesCannotReuseAnObjectKey() {
        var storage = new FileSystemDocumentObjectStorage(root);
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        storage.put("documents/a/files/b/v1", original, "application/pdf", "digest");
        storage.put("documents/a/files/b/v1", original, "application/pdf", "digest");

        assertThatThrownBy(() -> storage.put(
                        "documents/a/files/b/v1",
                        "different".getBytes(StandardCharsets.UTF_8),
                        "application/pdf",
                        "different-digest"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("collision");
        assertThat(storage.get("documents/a/files/b/v1")).isEqualTo(original);
    }
}
