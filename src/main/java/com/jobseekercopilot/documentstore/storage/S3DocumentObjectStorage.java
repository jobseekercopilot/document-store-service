package com.jobseekercopilot.documentstore.storage;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

@RequiredArgsConstructor
public class S3DocumentObjectStorage implements DocumentObjectStorage {
    private final S3Client client;
    private final String bucket;
    private final String kmsKeyId;

    @Override
    public void checkAvailability() {
        try {
            client.headBucket(
                    HeadBucketRequest.builder().bucket(bucket).build());
        } catch (SdkException exception) {
            throw new ObjectStorageException(
                    "Document object storage is unavailable", exception);
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType, String sha256) {
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .contentLength((long) content.length)
                            .metadata(Map.of("sha256", sha256))
                            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                            .ssekmsKeyId(kmsKeyId)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (SdkException exception) {
            throw new ObjectStorageException("Unable to store document object", exception);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            ResponseBytes<?> response = client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return response.asByteArray();
        } catch (SdkException exception) {
            throw new ObjectStorageException("Document object is unavailable", exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException exception) {
            throw new ObjectStorageException("Unable to delete document object", exception);
        }
    }

    @Override
    public int permanentlyDeleteKey(String key) {
        requireSafeScope(key, false);
        return permanentlyDelete(key, false);
    }

    @Override
    public int permanentlyDeletePrefix(String prefix) {
        requireSafeScope(prefix, true);
        return permanentlyDelete(prefix, true);
    }

    private int permanentlyDelete(String scope, boolean prefixScope) {
        int deleted = 0;
        String keyMarker = null;
        String versionMarker = null;
        for (int page = 0; page < 10_000; page++) {
            ListObjectVersionsResponse response = listVersions(
                    scope, keyMarker, versionMarker);
            for (ObjectVersion version : response.versions()) {
                if (matches(version.key(), scope, prefixScope)) {
                    deleteVersion(version.key(), version.versionId());
                    deleted++;
                }
            }
            for (DeleteMarkerEntry marker : response.deleteMarkers()) {
                if (matches(marker.key(), scope, prefixScope)) {
                    deleteVersion(marker.key(), marker.versionId());
                    deleted++;
                }
            }
            if (!Boolean.TRUE.equals(response.isTruncated())) {
                verifyNoVersions(scope, prefixScope);
                return deleted;
            }
            String nextKeyMarker = response.nextKeyMarker();
            String nextVersionMarker = response.nextVersionIdMarker();
            if (java.util.Objects.equals(keyMarker, nextKeyMarker)
                    && java.util.Objects.equals(versionMarker, nextVersionMarker)) {
                throw new ObjectStorageException(
                        "Permanent document object deletion did not make progress");
            }
            keyMarker = nextKeyMarker;
            versionMarker = nextVersionMarker;
        }
        throw new ObjectStorageException(
                "Permanent document object deletion exceeded its bounded page limit");
    }

    private ListObjectVersionsResponse listVersions(
            String prefix, String keyMarker, String versionMarker) {
        try {
            var request = ListObjectVersionsRequest.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .keyMarker(keyMarker)
                    .versionIdMarker(versionMarker)
                    .maxKeys(1000)
                    .build();
            return client.listObjectVersions(request);
        } catch (SdkException exception) {
            throw new ObjectStorageException(
                    "Unable to enumerate permanent document object deletion scope",
                    exception);
        }
    }

    private void deleteVersion(String key, String versionId) {
        try {
            var request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .versionId(versionId)
                    .build();
            client.deleteObject(request);
        } catch (SdkException exception) {
            throw new ObjectStorageException(
                    "Unable to permanently delete document object version", exception);
        }
    }

    private void verifyNoVersions(String scope, boolean prefixScope) {
        String keyMarker = null;
        String versionMarker = null;
        for (int page = 0; page < 10_000; page++) {
            ListObjectVersionsResponse response = listVersions(
                    scope, keyMarker, versionMarker);
            boolean versionPresent = response.versions().stream()
                    .map(ObjectVersion::key)
                    .anyMatch(key -> matches(key, scope, prefixScope));
            boolean markerPresent = response.deleteMarkers().stream()
                    .map(DeleteMarkerEntry::key)
                    .anyMatch(key -> matches(key, scope, prefixScope));
            if (versionPresent || markerPresent) {
                throw new ObjectStorageException(
                        "Unable to prove permanent document object deletion");
            }
            if (!Boolean.TRUE.equals(response.isTruncated())) {
                return;
            }
            String nextKeyMarker = response.nextKeyMarker();
            String nextVersionMarker = response.nextVersionIdMarker();
            if (java.util.Objects.equals(keyMarker, nextKeyMarker)
                    && java.util.Objects.equals(versionMarker, nextVersionMarker)) {
                throw new ObjectStorageException(
                        "Permanent document object verification did not make progress");
            }
            keyMarker = nextKeyMarker;
            versionMarker = nextVersionMarker;
        }
        throw new ObjectStorageException(
                "Permanent document object verification exceeded its bounded page limit");
    }

    private boolean matches(String key, String scope, boolean prefixScope) {
        return prefixScope ? key.startsWith(scope) : key.equals(scope);
    }

    private void requireSafeScope(String scope, boolean prefixScope) {
        boolean allowed = scope != null
                && (prefixScope
                        ? scope.matches(
                                "documents/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/")
                        : scope.matches(
                                "quarantine/application-uploads/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"));
        if (!allowed) {
            throw new ObjectStorageException("Invalid permanent-erasure object scope");
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException exception) {
            return false;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw new ObjectStorageException("Unable to inspect document object", exception);
        } catch (SdkException exception) {
            throw new ObjectStorageException("Unable to inspect document object", exception);
        }
    }

    @Override
    public ObjectKeyPage listKeys(String prefix, String afterKey, int limit) {
        if (prefix == null || prefix.isBlank() || limit < 1) {
            throw new ObjectStorageException("Invalid document object listing request");
        }
        try {
            var requestBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .maxKeys(limit);
            if (afterKey != null && !afterKey.isBlank()) {
                requestBuilder.startAfter(afterKey);
            }
            var request = requestBuilder.build();
            var response = client.listObjectsV2(request);
            var keys = response.contents().stream()
                    .map(object -> object.key())
                    .toList();
            String nextAfterKey = Boolean.TRUE.equals(response.isTruncated()) && !keys.isEmpty()
                    ? keys.get(keys.size() - 1)
                    : null;
            return new ObjectKeyPage(keys, nextAfterKey);
        } catch (SdkException exception) {
            throw new ObjectStorageException("Unable to list document objects", exception);
        }
    }
}
