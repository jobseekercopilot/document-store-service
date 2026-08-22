package com.jobseekercopilot.documentstore.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

@Testcontainers
class S3PermanentErasureIntegrationTest {

    private static final String BUCKET = "document-permanent-erasure-test";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.7.0"))
            .withServices(LocalStackContainer.Service.S3);

    private S3Client client;

    @BeforeEach
    void createVersionedBucket() {
        client = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(
                        LocalStackContainer.Service.S3))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                LOCALSTACK.getAccessKey(),
                                LOCALSTACK.getSecretKey())))
                .forcePathStyle(true)
                .build();
        if (client.listBuckets().buckets().stream()
                .noneMatch(bucket -> BUCKET.equals(bucket.name()))) {
            client.createBucket(CreateBucketRequest.builder()
                    .bucket(BUCKET)
                    .build());
        }
        client.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(BUCKET)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());
    }

    @Test
    void removesEveryCurrentNoncurrentVersionAndMarkerWithoutTouchingNeighbour() {
        UUID documentId = UUID.randomUUID();
        UUID neighbourId = UUID.randomUUID();
        String prefix = "documents/" + documentId + "/";
        String firstKey = prefix + "files/" + UUID.randomUUID() + "/v1";
        String secondKey = prefix + "files/" + UUID.randomUUID() + "/v2";
        String neighbourKey = "documents/" + neighbourId + "/files/"
                + UUID.randomUUID() + "/v1";
        put(firstKey, "first-v1");
        put(firstKey, "first-v2");
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(BUCKET)
                .key(firstKey)
                .build());
        put(secondKey, "second-v1");
        put(neighbourKey, "neighbour-v1");

        S3DocumentObjectStorage storage = new S3DocumentObjectStorage(
                client, BUCKET, "unused-for-delete-test");
        assertThat(storage.permanentlyDeletePrefix(prefix)).isEqualTo(4);

        var erased = client.listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(BUCKET)
                .prefix(prefix)
                .build());
        assertThat(erased.versions()).isEmpty();
        assertThat(erased.deleteMarkers()).isEmpty();
        var neighbour = client.listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(BUCKET)
                .prefix("documents/" + neighbourId + "/")
                .build());
        assertThat(neighbour.versions()).hasSize(1);
    }

    private void put(String key, String value) {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                RequestBody.fromBytes(value.getBytes(StandardCharsets.UTF_8)));
    }
}
