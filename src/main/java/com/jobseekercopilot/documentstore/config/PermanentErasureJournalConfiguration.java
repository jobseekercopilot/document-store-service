package com.jobseekercopilot.documentstore.config;

import com.jobseekercopilot.documentstore.storage.FileSystemPermanentErasureJournal;
import com.jobseekercopilot.documentstore.storage.PermanentErasureJournal;
import com.jobseekercopilot.documentstore.storage.S3PermanentErasureJournal;
import com.jobseekercopilot.documentstore.storage.UnavailablePermanentErasureJournal;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
public class PermanentErasureJournalConfiguration {

    @Bean(destroyMethod = "close", name = "permanentErasureJournalS3Client")
    @ConditionalOnProperty(
            name = "document-store.permanent-erasure-journal.provider",
            havingValue = "s3")
    S3Client permanentErasureJournalS3Client(
            PermanentErasureJournalProperties properties) {
        properties.requireConfigured();
        PermanentErasureJournalProperties.S3 s3 = properties.getS3();
        var builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(s3.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.isPathStyleAccess())
                        .build());
        boolean accessKeyPresent = StringUtils.hasText(s3.getAccessKey());
        boolean secretKeyPresent = StringUtils.hasText(s3.getSecretKey());
        switch (s3.getCredentialsProvider()) {
            case TASK_ROLE -> {
                if (accessKeyPresent || secretKeyPresent
                        || StringUtils.hasText(s3.getEndpoint())) {
                    throw new IllegalStateException(
                            "Permanent-erasure journal task-role credentials forbid static keys and custom endpoints");
                }
                builder.credentialsProvider(
                        ContainerCredentialsProvider.builder().build());
            }
            case STATIC -> {
                if (!accessKeyPresent || !secretKeyPresent) {
                    throw new IllegalStateException(
                            "Permanent-erasure journal static credentials require both keys");
                }
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                s3.getAccessKey(), s3.getSecretKey())));
                if (StringUtils.hasText(s3.getEndpoint())) {
                    builder.endpointOverride(URI.create(s3.getEndpoint()));
                }
            }
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "document-store.permanent-erasure-journal.provider",
            havingValue = "s3")
    PermanentErasureJournal s3PermanentErasureJournal(
            PermanentErasureJournalProperties properties,
            @Qualifier("permanentErasureJournalS3Client") S3Client client) {
        return new S3PermanentErasureJournal(
                client, properties.getS3().getBucket(), properties.getS3().getKmsKeyId());
    }

    @Bean
    @ConditionalOnProperty(
            name = "document-store.permanent-erasure-journal.provider",
            havingValue = "filesystem")
    PermanentErasureJournal filesystemPermanentErasureJournal(
            PermanentErasureJournalProperties properties) {
        properties.requireConfigured();
        return new FileSystemPermanentErasureJournal(properties.getFilesystemRoot());
    }

    @Bean
    @ConditionalOnProperty(
            name = "document-store.permanent-erasure-journal.provider",
            havingValue = "disabled",
            matchIfMissing = true)
    PermanentErasureJournal unavailablePermanentErasureJournal() {
        return new UnavailablePermanentErasureJournal();
    }
}
