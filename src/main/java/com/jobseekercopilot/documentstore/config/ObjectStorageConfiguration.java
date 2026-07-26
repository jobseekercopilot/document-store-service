package com.jobseekercopilot.documentstore.config;

import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.FileSystemDocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.S3DocumentObjectStorage;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
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
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.isPathStyleAccess())
                        .build());
        if (s3.getEndpoint() != null && !s3.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(s3.getEndpoint()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "document-store.object-storage.provider",
            havingValue = "s3",
            matchIfMissing = true)
    DocumentObjectStorage s3DocumentObjectStorage(
            ObjectStorageProperties properties, S3Client s3Client) {
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
