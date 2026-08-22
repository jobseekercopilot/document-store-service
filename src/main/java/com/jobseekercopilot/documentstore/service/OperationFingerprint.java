package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class OperationFingerprint {
    private OperationFingerprint() {
    }

    static String sha256(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                byte[] value = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                digest.update(value);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    static String contentSha256(byte[] content) {
        return ObjectIntegrity.sha256(content);
    }
}
