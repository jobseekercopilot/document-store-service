package com.jobseekercopilot.documentstore.config;

import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.FileSystemDocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.S3DocumentObjectStorage;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
public class ObjectStorageConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            name = "document-store.object-storage.provider",
            havingValue = "s3",
            matchIfMissing = true)
    S3Client documentStoreS3Client(ObjectStorageProperties properties) {
        ObjectStorageProperties.S3 s3 = properties.getS3();
        var builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(documentStoreCredentialsProvider(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.isPathStyleAccess())
                        .build());
        if (s3.getEndpoint() != null && !s3.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(s3.getEndpoint()));
        }
        return builder.build();
    }

    AwsCredentialsProvider documentStoreCredentialsProvider(ObjectStorageProperties properties) {
        ObjectStorageProperties.S3 s3 = properties.getS3();
        boolean hasAccessKey = StringUtils.hasText(s3.getAccessKey());
        boolean hasSecretKey = StringUtils.hasText(s3.getSecretKey());
        return switch (s3.getCredentialsProvider()) {
            case TASK_ROLE -> {
                if (hasAccessKey || hasSecretKey) {
                    throw new IllegalStateException(
                            "Static S3 credentials are forbidden when task-role credentials are selected");
                }
                if (StringUtils.hasText(s3.getEndpoint())) {
                    throw new IllegalStateException(
                            "A custom S3 endpoint is forbidden when task-role credentials are selected");
                }
                yield ContainerCredentialsProvider.builder().build();
            }
            case STATIC -> {
                if (!hasAccessKey || !hasSecretKey) {
                    throw new IllegalStateException(
                            "Both static S3 credentials are required when the static provider is selected");
                }
                yield StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey()));
            }
        };
    }

    @Bean
    @ConditionalOnProperty(
            name = "document-store.object-storage.provider",
            havingValue = "s3",
            matchIfMissing = true)
    DocumentObjectStorage s3DocumentObjectStorage(
            ObjectStorageProperties properties,
            @Qualifier("documentStoreS3Client") S3Client s3Client) {
        return new S3DocumentObjectStorage(
                s3Client,
                properties.getS3().getBucket(),
                properties.getS3().getKmsKeyId());
    }

    @Bean
    @ConditionalOnProperty(
            name = "document-store.object-storage.provider",
            havingValue = "filesystem")
    DocumentObjectStorage filesystemDocumentObjectStorage(ObjectStorageProperties properties) {
        return new FileSystemDocumentObjectStorage(properties.getFilesystemRoot());
    }
}
