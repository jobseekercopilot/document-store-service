package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentOwnerFingerprint {

    private static final String KEY_VERIFICATION_VALUE =
            "job-seeker-copilot:document-erasure-write-fence:v1";

    private final DocumentRetentionProperties properties;

    public String fingerprint(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("Document owner is required.");
        }
        if (!configured()) {
            throw new IllegalStateException(
                    "Permanent-erasure owner fingerprinting is not configured.");
        }
        return hmac(ownerId.trim());
    }

    public String keyVerifier() {
        if (!configured()) {
            throw new IllegalStateException(
                    "Permanent-erasure owner fingerprinting is not configured.");
        }
        return hmac(KEY_VERIFICATION_VALUE);
    }

    public boolean configured() {
        String key = properties.getErasureFingerprintKey();
        return key != null
                && key.length() >= 32
                && key.chars().noneMatch(Character::isISOControl);
    }

    private String hmac(String value) {
        String key = properties.getErasureFingerprintKey();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Permanent-erasure owner fingerprinting is unavailable.",
                    exception);
        }
    }
}
