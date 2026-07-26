package com.jobseekercopilot.documentstore.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

class S3DocumentObjectStorageTest {
    @Test
    void writesPrivateObjectWithManagedKmsEncryptionAndIntegrityMetadata() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        byte[] content = "encrypted-object".getBytes(StandardCharsets.UTF_8);
        String sha256 = ObjectIntegrity.sha256(content);

        new S3DocumentObjectStorage(client, "private-documents", "managed-key-id")
                .put("documents/a/files/b/v1", content, "application/pdf", sha256);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("private-documents");
        assertThat(request.getValue().acl()).isNull();
        assertThat(request.getValue().serverSideEncryption())
                .isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(request.getValue().ssekmsKeyId()).isEqualTo("managed-key-id");
        assertThat(request.getValue().metadata()).containsEntry("sha256", sha256);
        assertThat(request.getValue().contentLength()).isEqualTo(content.length);
    }

    @Test
    void listsOnlyOneBoundedPrefixPageUsingAStableStartAfterCursor() {
        S3Client client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(
                                S3Object.builder().key("documents/a/files/1/v1").build(),
                                S3Object.builder().key("documents/b/files/2/v1").build())
                        .isTruncated(true)
                        .build());

        ObjectKeyPage page =
                new S3DocumentObjectStorage(client, "private-documents", "managed-key-id")
                        .listKeys("documents/", "documents/0", 2);

        ArgumentCaptor<ListObjectsV2Request> request =
                ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client).listObjectsV2(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("private-documents");
        assertThat(request.getValue().prefix()).isEqualTo("documents/");
        assertThat(request.getValue().startAfter()).isEqualTo("documents/0");
        assertThat(request.getValue().maxKeys()).isEqualTo(2);
        assertThat(page.keys()).containsExactly(
                "documents/a/files/1/v1",
                "documents/b/files/2/v1");
        assertThat(page.nextAfterKey()).isEqualTo("documents/b/files/2/v1");
    }

    @Test
    void omitsStartAfterForTheFirstInventoryPage() {
        S3Client client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder()
                                .key("documents/a/files/1/v1")
                                .build())
                        .isTruncated(false)
                        .build());

        new S3DocumentObjectStorage(client, "private-documents", "managed-key-id")
                .listKeys("documents/", null, 50);

        ArgumentCaptor<ListObjectsV2Request> request =
                ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client).listObjectsV2(request.capture());
        assertThat(request.getValue().startAfter()).isNull();
    }
}
