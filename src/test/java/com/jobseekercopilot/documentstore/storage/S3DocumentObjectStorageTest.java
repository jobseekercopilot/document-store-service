package com.jobseekercopilot.documentstore.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

class S3DocumentObjectStorageTest {
    @Test
    void availabilityCheckUsesTheConfiguredPrivateBucket() {
        S3Client client = mock(S3Client.class);
        when(client.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());

        new S3DocumentObjectStorage(
                        client, "private-documents", "managed-key-id")
                .checkAvailability();

        ArgumentCaptor<HeadBucketRequest> request =
                ArgumentCaptor.forClass(HeadBucketRequest.class);
        verify(client).headBucket(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("private-documents");
    }

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

    @Test
    void permanentlyDeletesEveryExactKeyVersionAndDeleteMarkerThenVerifiesAbsence() {
        S3Client client = mock(S3Client.class);
        String key = "quarantine/application-uploads/10000000-0000-4000-8000-000000000001";
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(
                        ListObjectVersionsResponse.builder()
                                .versions(
                                        ObjectVersion.builder()
                                                .key(key)
                                                .versionId("version-2")
                                                .build(),
                                        ObjectVersion.builder()
                                                .key(key + "-foreign")
                                                .versionId("foreign-version")
                                                .build())
                                .deleteMarkers(DeleteMarkerEntry.builder()
                                        .key(key)
                                        .versionId("delete-marker-1")
                                        .build())
                                .isTruncated(false)
                                .build(),
                        ListObjectVersionsResponse.builder()
                                .isTruncated(false)
                                .build());
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        int deleted = new S3DocumentObjectStorage(
                        client, "private-documents", "managed-key-id")
                .permanentlyDeleteKey(key);

        assertThat(deleted).isEqualTo(2);
        ArgumentCaptor<DeleteObjectRequest> deletes =
                ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client, times(2)).deleteObject(deletes.capture());
        assertThat(deletes.getAllValues())
                .extracting(DeleteObjectRequest::key, DeleteObjectRequest::versionId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(key, "version-2"),
                        org.assertj.core.groups.Tuple.tuple(key, "delete-marker-1"));
        assertThat(deletes.getAllValues())
                .noneMatch(request -> request.key().endsWith("-foreign"));
    }

    @Test
    void permanentlyDeletesOnlyTheExactBoundedDocumentPrefixAcrossPages() {
        S3Client client = mock(S3Client.class);
        String prefix = "documents/10000000-0000-4000-8000-000000000001/";
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(
                        ListObjectVersionsResponse.builder()
                                .versions(ObjectVersion.builder()
                                        .key(prefix + "files/a/v1")
                                        .versionId("v1")
                                        .build())
                                .isTruncated(true)
                                .nextKeyMarker(prefix + "files/a/v1")
                                .nextVersionIdMarker("v1")
                                .build(),
                        ListObjectVersionsResponse.builder()
                                .deleteMarkers(DeleteMarkerEntry.builder()
                                        .key(prefix + "files/b/v2")
                                        .versionId("marker")
                                        .build())
                                .isTruncated(false)
                                .build(),
                        ListObjectVersionsResponse.builder()
                                .isTruncated(false)
                                .build());
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        int deleted = new S3DocumentObjectStorage(
                        client, "private-documents", "managed-key-id")
                .permanentlyDeletePrefix(prefix);

        assertThat(deleted).isEqualTo(2);
        ArgumentCaptor<ListObjectVersionsRequest> lists =
                ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(client, times(3)).listObjectVersions(lists.capture());
        assertThat(lists.getAllValues())
                .allMatch(request -> request.bucket().equals("private-documents")
                        && request.prefix().equals(prefix)
                        && request.maxKeys() == 1000);
        assertThat(lists.getAllValues().get(1).keyMarker())
                .isEqualTo(prefix + "files/a/v1");
        assertThat(lists.getAllValues().get(1).versionIdMarker())
                .isEqualTo("v1");
    }

    @Test
    void permanentErasureRejectsBroadOrForeignScopesBeforeCallingS3() {
        S3Client client = mock(S3Client.class);
        S3DocumentObjectStorage storage = new S3DocumentObjectStorage(
                client, "private-documents", "managed-key-id");

        assertThatThrownBy(() -> storage.permanentlyDeletePrefix("documents/"))
                .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> storage.permanentlyDeleteKey("other/private"))
                .isInstanceOf(ObjectStorageException.class);
    }

    @Test
    void deniedVersionEnumerationOrDeletionFailsClosed() {
        String prefix = "documents/10000000-0000-4000-8000-000000000001/";
        S3Client listDenied = mock(S3Client.class);
        when(listDenied.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenThrow(S3Exception.builder()
                        .statusCode(403)
                        .message("access denied")
                        .build());
        assertThatThrownBy(() -> new S3DocumentObjectStorage(
                        listDenied, "private-documents", "managed-key-id")
                .permanentlyDeletePrefix(prefix))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("enumerate");
        verify(listDenied, never()).deleteObject(any(DeleteObjectRequest.class));

        S3Client deleteDenied = mock(S3Client.class);
        when(deleteDenied.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder()
                        .versions(ObjectVersion.builder()
                                .key(prefix + "files/a/v1")
                                .versionId("v1")
                                .build())
                        .isTruncated(false)
                        .build());
        when(deleteDenied.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder()
                        .statusCode(403)
                        .message("access denied")
                        .build());
        assertThatThrownBy(() -> new S3DocumentObjectStorage(
                        deleteDenied, "private-documents", "managed-key-id")
                .permanentlyDeletePrefix(prefix))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("permanently delete");
    }
}
