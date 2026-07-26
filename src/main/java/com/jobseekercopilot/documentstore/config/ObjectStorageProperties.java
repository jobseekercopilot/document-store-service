package com.jobseekercopilot.documentstore.config;

import java.nio.file.Path;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "document-store.object-storage")
public class ObjectStorageProperties {
    private Provider provider = Provider.S3;
    private Path filesystemRoot;
    private final S3 s3 = new S3();

    public enum Provider {
        S3,
        FILESYSTEM
    }

    @Data
    public static class S3 {
        private String endpoint;
        private String region;
        private String bucket;
        private String accessKey;
        private String secretKey;
        private String kmsKeyId;
        private boolean pathStyleAccess;
    }
}
