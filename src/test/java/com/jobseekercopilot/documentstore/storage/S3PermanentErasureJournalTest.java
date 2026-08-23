package com.jobseekercopilot.documentstore.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

class S3PermanentErasureJournalTest {

    private static final String BUCKET = "immutable-erasure-journal";
    private static final String KMS_KEY = "managed-erasure-journal-key";

    @Test
    void conditionallyWritesThenReadsBackExactKmsEncryptedVersion() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("journal-v1").build());
        UUID operationId = UUID.randomUUID();
        byte[] content = canonicalContent(operationId);
        String sha256 = ObjectIntegrity.sha256(content);
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(response(content, sha256, "journal-v1"));

        PermanentErasureJournalEntry evidence = new S3PermanentErasureJournal(
                        client, BUCKET, KMS_KEY)
                .writeOrVerify(operationId, content, sha256);

        ArgumentCaptor<PutObjectRequest> request =
                ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key())
                .isEqualTo(PermanentErasureJournalKeys.forOperation(operationId));
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(request.getValue().serverSideEncryption())
                .isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(request.getValue().ssekmsKeyId()).isEqualTo(KMS_KEY);
        assertThat(request.getValue().bucketKeyEnabled()).isTrue();
        assertThat(request.getValue().metadata())
                .containsEntry("content-sha256", sha256);
        ArgumentCaptor<GetObjectRequest> readRequest =
                ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObjectAsBytes(readRequest.capture());
        assertThat(readRequest.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(readRequest.getValue().key())
                .isEqualTo(PermanentErasureJournalKeys.forOperation(operationId));
        assertThat(readRequest.getValue().versionId()).isEqualTo("journal-v1");
        assertThat(evidence.objectVersion()).isEqualTo("journal-v1");
        assertThat(evidence.contentSha256()).isEqualTo(sha256);
    }

    @Test
    void ambiguousConditionalWriteAcceptsOnlyExactExistingCanonicalVersion() {
        S3Client client = mock(S3Client.class);
        UUID operationId = UUID.randomUUID();
        byte[] content = canonicalContent(operationId);
        String sha256 = ObjectIntegrity.sha256(content);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder()
                        .statusCode(503)
                        .message("ambiguous timeout")
                        .build());
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(response(content, sha256, "journal-v1"));

        PermanentErasureJournalEntry evidence = new S3PermanentErasureJournal(
                        client, BUCKET, KMS_KEY)
                .writeOrVerify(operationId, content, sha256);

        assertThat(evidence.objectVersion()).isEqualTo("journal-v1");
        assertThat(evidence.canonicalContent()).isEqualTo(content);
    }

    @Test
    void ambiguousWriteRejectsMismatchedExistingObject() {
        S3Client client = mock(S3Client.class);
        UUID operationId = UUID.randomUUID();
        byte[] expected = canonicalContent(operationId);
        byte[] conflicting = "{\"conflict\":true}"
                .getBytes(StandardCharsets.UTF_8);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).build());
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(response(
                        conflicting,
                        ObjectIntegrity.sha256(conflicting),
                        "foreign-version"));

        assertThatThrownBy(() -> new S3PermanentErasureJournal(
                        client, BUCKET, KMS_KEY)
                .writeOrVerify(
                        operationId, expected, ObjectIntegrity.sha256(expected)))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("collision");
    }

    @Test
    void missingVersionWrongKmsOrDisabledBucketKeyEvidenceFailsClosed() {
        S3Client missingVersion = mock(S3Client.class);
        byte[] content = canonicalContent(UUID.randomUUID());
        when(missingVersion.putObject(
                        any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        assertThatThrownBy(() -> new S3PermanentErasureJournal(
                        missingVersion, BUCKET, KMS_KEY)
                .writeOrVerify(
                        UUID.randomUUID(), content, ObjectIntegrity.sha256(content)))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("version evidence");

        when(missingVersion.putObject(
                        any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("null").build());
        assertThatThrownBy(() -> new S3PermanentErasureJournal(
                        missingVersion, BUCKET, KMS_KEY)
                .writeOrVerify(
                        UUID.randomUUID(), content, ObjectIntegrity.sha256(content)))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("version evidence");

        S3Client wrongKms = mock(S3Client.class);
        UUID operationId = UUID.randomUUID();
        when(wrongKms.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder()
                                .versionId("journal-v1")
                                .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                                .ssekmsKeyId("wrong-key")
                                .bucketKeyEnabled(true)
                                .metadata(Map.of(
                                        "content-sha256",
                                        ObjectIntegrity.sha256(content)))
                                .build(),
                        content));
        assertThatThrownBy(() -> new S3PermanentErasureJournal(
                        wrongKms, BUCKET, KMS_KEY)
                .read(operationId, "journal-v1"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("encryption evidence");

        S3Client disabledBucketKey = mock(S3Client.class);
        when(disabledBucketKey.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder()
                                .versionId("journal-v1")
                                .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                                .ssekmsKeyId(KMS_KEY)
                                .bucketKeyEnabled(false)
                                .metadata(Map.of(
                                        "content-sha256",
                                        ObjectIntegrity.sha256(content)))
                                .build(),
                        content));
        assertThatThrownBy(() -> new S3PermanentErasureJournal(
                        disabledBucketKey, BUCKET, KMS_KEY)
                .read(operationId, "journal-v1"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("encryption evidence");
    }

    private ResponseBytes<GetObjectResponse> response(
            byte[] content, String sha256, String version) {
        return ResponseBytes.fromByteArray(
                GetObjectResponse.builder()
                        .versionId(version)
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                        .ssekmsKeyId(KMS_KEY)
                        .bucketKeyEnabled(true)
                        .metadata(Map.of("content-sha256", sha256))
                        .build(),
                content);
    }

    private byte[] canonicalContent(UUID operationId) {
        return ("{\"operationId\":\"" + operationId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
