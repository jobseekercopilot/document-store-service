package com.jobseekercopilot.documentstore.storage;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.S3Exception;

@RequiredArgsConstructor
public class S3DocumentObjectStorage implements DocumentObjectStorage {
    private final S3Client client;
    private final String bucket;
    private final String kmsKeyId;

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
}
