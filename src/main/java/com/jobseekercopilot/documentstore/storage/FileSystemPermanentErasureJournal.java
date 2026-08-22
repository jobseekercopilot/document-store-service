package com.jobseekercopilot.documentstore.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

public class FileSystemPermanentErasureJournal implements PermanentErasureJournal {

    private static final int MAX_RECORD_BYTES = 4_194_304;
    private final Path root;

    public FileSystemPermanentErasureJournal(Path root) {
        if (root == null) {
            throw new IllegalStateException(
                    "Permanent-erasure filesystem journal root is required");
        }
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new ObjectStorageException(
                    "Unable to initialise permanent-erasure recovery journal", exception);
        }
    }

    @Override
    public PermanentErasureJournalEntry writeOrVerify(
            UUID operationId, byte[] canonicalContent, String contentSha256) {
        requireContent(canonicalContent, contentSha256);
        String key = PermanentErasureJournalKeys.forOperation(operationId);
        Path target = resolve(key);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                temporary = Files.createTempFile(target.getParent(), ".journal-", ".tmp");
                Files.write(temporary, canonicalContent, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    temporary = null;
                } catch (java.nio.file.FileAlreadyExistsException race) {
                    Files.deleteIfExists(temporary);
                    temporary = null;
                }
            }
            byte[] persisted = Files.readAllBytes(target);
            requireExactContent(persisted, canonicalContent, contentSha256);
            return new PermanentErasureJournalEntry(
                    key,
                    "filesystem-" + contentSha256,
                    contentSha256,
                    persisted);
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new ObjectStorageException(
                    "Unable to persist permanent-erasure recovery journal", exception);
        }
    }

    @Override
    public PermanentErasureJournalEntry read(UUID operationId, String versionId) {
        String key = PermanentErasureJournalKeys.forOperation(operationId);
        try {
            byte[] content = Files.readAllBytes(resolve(key));
            String sha256 = sha256(content);
            String actualVersion = "filesystem-" + sha256;
            if (versionId != null && !versionId.equals(actualVersion)) {
                throw new ObjectStorageException(
                        "Permanent-erasure recovery journal version does not match retained evidence");
            }
            requireContent(content, sha256);
            return new PermanentErasureJournalEntry(
                    key, actualVersion, sha256, content);
        } catch (IOException exception) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal is unavailable", exception);
        }
    }

    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new ObjectStorageException(
                    "Invalid permanent-erasure recovery journal key");
        }
        return resolved;
    }

    private void requireContent(byte[] content, String expectedSha256) {
        if (content == null || content.length < 2 || content.length > MAX_RECORD_BYTES) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal content is invalid");
        }
        if (expectedSha256 == null
                || !expectedSha256.matches("[0-9a-f]{64}")
                || !MessageDigest.isEqual(
                        expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        sha256(content).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal digest is invalid");
        }
    }

    private void requireExactContent(
            byte[] persisted, byte[] expected, String expectedSha256) {
        if (!Arrays.equals(persisted, expected)) {
            throw new ObjectStorageException(
                    "Permanent-erasure recovery journal key collision");
        }
        requireContent(persisted, expectedSha256);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Preserve the original storage failure.
        }
    }
}
