package com.jobseekercopilot.documentstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

class ObjectStorageConfigurationTest {
    private final ObjectStorageConfiguration configuration = new ObjectStorageConfiguration();

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
}
