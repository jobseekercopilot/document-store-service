package com.jobseekercopilot.documentstore.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemPermanentErasureJournalTest {

    @TempDir
    Path root;

    @Test
    void repeatedExactWriteReturnsOneDeterministicVersion() {
        UUID operationId = UUID.randomUUID();
        byte[] content = "{\"schemaVersion\":\"v1\"}"
                .getBytes(StandardCharsets.UTF_8);
        String sha256 = ObjectIntegrity.sha256(content);
        FileSystemPermanentErasureJournal journal =
                new FileSystemPermanentErasureJournal(root);

        var first = journal.writeOrVerify(operationId, content, sha256);
        var replay = journal.writeOrVerify(operationId, content, sha256);

        assertThat(replay).isEqualTo(first);
        assertThat(journal.read(operationId, first.objectVersion()))
                .isEqualTo(first);
    }

    @Test
    void sameOperationWithDifferentContentFailsClosed() {
        UUID operationId = UUID.randomUUID();
        FileSystemPermanentErasureJournal journal =
                new FileSystemPermanentErasureJournal(root);
        byte[] first = "{\"record\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"record\":2}".getBytes(StandardCharsets.UTF_8);
        journal.writeOrVerify(operationId, first, ObjectIntegrity.sha256(first));

        assertThatThrownBy(() -> journal.writeOrVerify(
                        operationId, second, ObjectIntegrity.sha256(second)))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("collision");
    }
}
