package com.jobseekercopilot.documentstore.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemDocumentObjectStorageTest {
    @TempDir
    Path root;

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

    @Test
    void listsBoundedStablePagesWithoutLeavingTheDocumentPrefix() {
        var storage = new FileSystemDocumentObjectStorage(root);
        byte[] content = "page".getBytes(StandardCharsets.UTF_8);
        storage.put("documents/b/files/2/v1", content, "application/pdf", "digest");
        storage.put("documents/a/files/1/v1", content, "application/pdf", "digest");
        storage.put("other/ignored", content, "application/pdf", "digest");

        ObjectKeyPage first = storage.listKeys("documents/", null, 1);
        ObjectKeyPage second = storage.listKeys("documents/", first.nextAfterKey(), 1);

        assertThat(first.keys()).containsExactly("documents/a/files/1/v1");
        assertThat(first.nextAfterKey()).isEqualTo("documents/a/files/1/v1");
        assertThat(second.keys()).containsExactly("documents/b/files/2/v1");
        assertThat(second.nextAfterKey()).isNull();
    }
}
