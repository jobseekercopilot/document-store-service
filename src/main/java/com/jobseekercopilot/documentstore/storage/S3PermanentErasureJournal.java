package com.jobseekercopilot.documentstore.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

public class S3PermanentErasureJournal implements PermanentErasureJournal {

    private static final int MAX_RECORD_BYTES = 4_194_304;
    private final S3Client client;
    private final String bucket;
    private final String kmsKeyId;

    public S3PermanentErasureJournal(
            S3Client client, String bucket, String kmsKeyId) {
        this.client = client;
        this.bucket = bucket;
        this.kmsKeyId = kmsKeyId;
    }

    @Override
    public PermanentErasureJournalEntry writeOrVerify(
            UUID operationId, byte[] canonicalContent, String contentSha256) {
        requireContent(canonicalContent, contentSha256);
        String key = PermanentErasureJournalKeys.forOperation(operationId);
        try {
            PutObjectResponse response = client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .ifNoneMatch("*")
                            .contentType("application/json")
                            .contentLength((long) canonicalContent.length)
                            .checksumSHA256(base64Sha256(canonicalContent))
                            .metadata(Map.of("content-sha256", contentSha256))
                            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                            .ssekmsKeyId(kmsKeyId)
                            .bucketKeyEnabled(true)
                            .build(),
                    RequestBody.fromBytes(canonicalContent));
            requireVersion(response.versionId());
            PermanentErasureJournalEntry persisted = read(
                    operationId, response.versionId());
            requireExactContent(persisted, canonicalContent, contentSha256);
            return persisted;
        } catch (SdkException ambiguousOrCollision) {
            try {
                PermanentErasureJournalEntry existing = read(operationId, null);
                requireExactContent(existing, canonicalContent, contentSha256);
                return existing;
            } catch (RuntimeException reconciliationFailure) {
                reconciliationFailure.addSuppressed(ambiguousOrCollision);
                throw reconciliationFailure;
            }
        }
    }

    @Override
    public PermanentErasureJournalEntry read(UUID operationId, String versionId) {
        String key = PermanentErasureJournalKeys.forOperation(operationId);
        try {
            var request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key);
            if (versionId != null && !versionId.isBlank()) {
                request.versionId(versionId);
            }
            ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(
                    request.build());
            byte[] content = response.asByteArray();
            String contentSha256 = sha256(content);
            requireContent(content, contentSha256);
            requireVersion(response.response().versionId());
            if (response.response().serverSideEncryption() != ServerSideEncryption.AWS_KMS
                    || !kmsKeyId.equals(response.response().ssekmsKeyId())) {
                throw new ObjectStorageException(
                        "Permanent-erasure recovery journal encryption evidence is invalid");
            }
            String metadataSha256 = response.response().metadata()
                    .get("content-sha256");
            if (metadataSha256 == null
                    || !MessageDigest.isEqual(
                            metadataSha256.getBytes(StandardCharsets.US_ASCII),
                            contentSha256.getBytes(StandardCharsets.US_ASCII))) {
                throw new ObjectStorageException(
                        "Permanent-erasure recovery journal metadata is invalid");
            }
            return new PermanentErasureJournalEntry(
                    key,
                    response.response().versionId(),
                    contentSha256,
                    content);
        } catch (ObjectStorageException exception) {
            throw exception;
        } catch (SdkException exception) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal is unavailable", exception);
        }
    }

    private void requireContent(byte[] content, String expectedSha256) {
        if (content == null || content.length < 2 || content.length > MAX_RECORD_BYTES) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal content is invalid");
        }
        if (expectedSha256 == null
                || !expectedSha256.matches("[0-9a-f]{64}")
                || !MessageDigest.isEqual(
                        expectedSha256.getBytes(StandardCharsets.US_ASCII),
                        sha256(content).getBytes(StandardCharsets.US_ASCII))) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal digest is invalid");
        }
    }

    private void requireVersion(String versionId) {
        if (versionId == null
                || versionId.isBlank()
                || "null".equalsIgnoreCase(versionId)
                || versionId.length() > 256) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal version evidence is unavailable");
        }
    }

    private void requireExactContent(
            PermanentErasureJournalEntry persisted,
            byte[] expectedContent,
            String expectedSha256) {
        if (!MessageDigest.isEqual(
                        persisted.contentSha256().getBytes(StandardCharsets.US_ASCII),
                        expectedSha256.getBytes(StandardCharsets.US_ASCII))
                || !Arrays.equals(
                        persisted.canonicalContent(), expectedContent)) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal key collision");
        }
    }

    private String base64Sha256(byte[] content) {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
