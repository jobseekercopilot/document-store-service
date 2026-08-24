package com.jobseekercopilot.documentstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.S3DocumentObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

class ObjectStorageConfigurationTest {
    private final ObjectStorageConfiguration configuration = new ObjectStorageConfiguration();

    @Test
    void selectsTheDocumentStoreClientWhenAnotherS3ClientExists() {
        new ApplicationContextRunner()
                .withUserConfiguration(ObjectStorageConfiguration.class)
                .withPropertyValues("document-store.object-storage.provider=s3")
                .withBean(ObjectStorageProperties.class, this::configuredS3Properties)
                .withBean(
                        "permanentErasureJournalS3Client",
                        S3Client.class,
                        () -> mock(S3Client.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DocumentObjectStorage.class);
                    assertThat(context.getBeansOfType(S3Client.class))
                            .containsOnlyKeys(
                                    "documentStoreS3Client",
                                    "permanentErasureJournalS3Client");

                    S3DocumentObjectStorage storage =
                            context.getBean(S3DocumentObjectStorage.class);
                    assertThat(ReflectionTestUtils.getField(storage, "client"))
                            .isSameAs(context.getBean("documentStoreS3Client"));
                });
    }

    @Test
    void usesOnlyTheEcsContainerCredentialProviderForTaskRoleMode() {
        ObjectStorageProperties properties = properties(
                ObjectStorageProperties.CredentialsProvider.TASK_ROLE, null, null);

        assertThat(configuration.documentStoreCredentialsProvider(properties))
                .isInstanceOf(ContainerCredentialsProvider.class);
    }

    @Test
    void createsStaticProviderOnlyWhenBothCredentialsArePresent() {
        ObjectStorageProperties properties = properties(
                ObjectStorageProperties.CredentialsProvider.STATIC,
                "local-access",
                "local-secret");

        assertThat(configuration.documentStoreCredentialsProvider(properties))
                .isInstanceOf(StaticCredentialsProvider.class);

        properties.getS3().setSecretKey(null);
        assertThatThrownBy(() -> configuration.documentStoreCredentialsProvider(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Both static S3 credentials");
    }

    @Test
    void taskRoleRejectsStaticCredentialsAndCustomEndpoints() {
        ObjectStorageProperties credentials = properties(
                ObjectStorageProperties.CredentialsProvider.TASK_ROLE,
                "must-not-be-used",
                "must-not-be-used");
        assertThatThrownBy(() -> configuration.documentStoreCredentialsProvider(credentials))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Static S3 credentials are forbidden");

        ObjectStorageProperties endpoint = properties(
                ObjectStorageProperties.CredentialsProvider.TASK_ROLE, null, null);
        endpoint.getS3().setEndpoint("https://objects.example");
        assertThatThrownBy(() -> configuration.documentStoreCredentialsProvider(endpoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("custom S3 endpoint is forbidden");
    }

    private ObjectStorageProperties properties(
            ObjectStorageProperties.CredentialsProvider provider,
            String accessKey,
            String secretKey) {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.getS3().setCredentialsProvider(provider);
        properties.getS3().setAccessKey(accessKey);
        properties.getS3().setSecretKey(secretKey);
        return properties;
    }

    private ObjectStorageProperties configuredS3Properties() {
        ObjectStorageProperties properties = properties(
                ObjectStorageProperties.CredentialsProvider.STATIC,
                "test-access-key",
                "test-secret-key");
        properties.getS3().setRegion("eu-west-2");
        properties.getS3().setBucket("document-bucket");
        properties.getS3().setKmsKeyId("test-kms-key");
        return properties;
    }
}
