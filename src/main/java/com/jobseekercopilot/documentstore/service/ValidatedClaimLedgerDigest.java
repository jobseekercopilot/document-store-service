package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.ValidatedClaimLedger;
import com.jobseekercopilot.documentstore.dto.ValidatedClaimLedger.ValidatedClaim;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class ValidatedClaimLedgerDigest {

    private static final String DIGEST_FORMAT = "claim-ledger-v1";

    private ValidatedClaimLedgerDigest() {
    }

    public static String calculate(ValidatedClaimLedger ledger) {
        MessageDigest digest = sha256();
        update(digest, DIGEST_FORMAT);
        update(digest, ledger.policyVersion());
        update(digest, ledger.parserVersion());
        update(digest, ledger.claims().size());
        for (ValidatedClaim claim : ledger.claims()) {
            update(digest, claim.claimId());
            update(digest, claim.disposition().name());
            update(digest, claim.evidenceIds());
            update(digest, claim.contentPaths());
            update(digest, claim.reviewText());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(
            MessageDigest digest, List<String> values) {
        update(digest, values.size());
        values.forEach(value -> update(digest, value));
    }

    private static void update(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value)
                .array());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable.", impossible);
        }
    }
}
