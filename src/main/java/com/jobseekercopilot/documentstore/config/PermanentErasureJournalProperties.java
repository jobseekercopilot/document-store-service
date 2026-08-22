package com.jobseekercopilot.documentstore.config;

import java.nio.file.Path;
import java.util.regex.Pattern;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "document-store.permanent-erasure-journal")
public class PermanentErasureJournalProperties {

    private static final Pattern REVIEWED_POLICY_VERSION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private Provider provider = Provider.DISABLED;
    private Path filesystemRoot;
    private boolean objectLockEnabled;
    private String retentionPolicyVersion = "UNAPPROVED";
    private final S3 s3 = new S3();

    public enum Provider {
        DISABLED,
        FILESYSTEM,
        S3
    }

    public enum CredentialsProvider {
        STATIC,
        TASK_ROLE
    }

    @Data
    public static class S3 {
        private CredentialsProvider credentialsProvider = CredentialsProvider.STATIC;
        private String endpoint;
        private String region;
        private String bucket;
        @ToString.Exclude
        private String accessKey;
        @ToString.Exclude
        private String secretKey;
        private String kmsKeyId;
        private boolean pathStyleAccess;
    }

    public void requireConfigured() {
        if (provider == Provider.DISABLED) {
            throw new IllegalStateException(
                    "Permanent-erasure recovery journaling is disabled.");
        }
        if (provider == Provider.FILESYSTEM) {
            if (filesystemRoot == null) {
                throw new IllegalStateException(
                        "Permanent-erasure filesystem journal root is required.");
            }
            return;
        }
        if (!objectLockEnabled
                || !isReviewedRetentionPolicyVersion(retentionPolicyVersion)
                || s3.region == null
                || s3.region.isBlank()
                || s3.bucket == null
                || s3.bucket.isBlank()
                || s3.kmsKeyId == null
                || s3.kmsKeyId.isBlank()) {
            throw new IllegalStateException(
                    "Permanent-erasure S3 recovery journaling requires reviewed immutable retention and encryption configuration.");
        }
    }

    static boolean isReviewedRetentionPolicyVersion(String value) {
        return value != null
                && REVIEWED_POLICY_VERSION.matcher(value).matches()
                && !"UNAPPROVED".equalsIgnoreCase(value);
    }
}
