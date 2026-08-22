package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentRetentionProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
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
        return hmac(primaryKey(), normalizeOwner(ownerId));
    }

    public List<String> fingerprints(String ownerId) {
        String normalizedOwner = normalizeOwner(ownerId);
        return keyRing().stream()
                .map(key -> hmac(key, normalizedOwner))
                .sorted()
                .toList();
    }

    public String keyVerifier() {
        return hmac(primaryKey(), KEY_VERIFICATION_VALUE);
    }

    public Set<String> keyVerifiers() {
        List<String> verifiers = keyRing().stream()
                .map(key -> hmac(key, KEY_VERIFICATION_VALUE))
                .toList();
        if (new HashSet<>(verifiers).size() != verifiers.size()) {
            throw new IllegalStateException(
                    "Permanent-erasure fingerprint keys do not have unique verifiers.");
        }
        return Set.copyOf(verifiers);
    }

    public boolean configured() {
        try {
            keyRing();
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private String primaryKey() {
        return keyRing().get(0);
    }

    private List<String> keyRing() {
        String primary = requireSecret(
                properties.getErasureFingerprintKey(), "primary");
        ArrayList<String> keys = new ArrayList<>();
        keys.add(primary);
        String previous = properties.getErasureFingerprintPreviousKeys();
        if (previous != null && !previous.isEmpty()) {
            String[] values = previous.split(",", -1);
            if (values.length > 8) {
                throw new IllegalStateException(
                        "At most eight previous permanent-erasure fingerprint keys may be configured.");
            }
            for (String value : values) {
                keys.add(requireSecret(value, "previous"));
            }
        }
        if (new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalStateException(
                    "Permanent-erasure fingerprint keys must be distinct.");
        }
        return List.copyOf(keys);
    }

    private String requireSecret(String value, String role) {
        if (value == null
                || value.length() < 32
                || value.length() > 512
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException(
                    "The permanent-erasure " + role
                            + " fingerprint key must be 32-512 base64url-safe characters.");
        }
        return value;
    }

    private String normalizeOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("Document owner is required.");
        }
        return ownerId.trim();
    }

    private String hmac(String key, String value) {
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
