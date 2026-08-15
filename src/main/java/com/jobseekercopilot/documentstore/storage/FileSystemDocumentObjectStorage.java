package com.jobseekercopilot.documentstore.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class FileSystemDocumentObjectStorage implements DocumentObjectStorage {
    private final Path root;

    public FileSystemDocumentObjectStorage(Path root) {
        if (root == null) {
            throw new IllegalStateException("Filesystem object-storage root is required");
        }
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new ObjectStorageException("Unable to initialise isolated object storage", exception);
        }
    }

    @Override
    public void checkAvailability() {
        if (!Files.isDirectory(root)
                || !Files.isReadable(root)
                || !Files.isWritable(root)) {
            throw new ObjectStorageException(
                    "Isolated document object storage is unavailable");
        }
    }

    @Override
    public void put(String key, byte[] content, String contentType, String sha256) {
        Path target = resolve(key);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                requireSameContent(target, content);
                return;
            }
            temporary = Files.createTempFile(target.getParent(), ".object-", ".tmp");
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException race) {
                deleteQuietly(temporary);
                temporary = null;
                requireSameContent(target, content);
            }
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new ObjectStorageException("Unable to store document object", exception);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException exception) {
            throw new ObjectStorageException("Document object is unavailable", exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw new ObjectStorageException("Unable to delete document object", exception);
        }
    }

    @Override
    public int permanentlyDeleteKey(String key) {
        if (key == null || !key.matches(
                "quarantine/application-uploads/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
            throw new ObjectStorageException("Invalid permanent-erasure object key");
        }
        Path target = resolve(key);
        try {
            boolean present = Files.deleteIfExists(target);
            if (Files.exists(target)) {
                throw new ObjectStorageException(
                        "Unable to prove permanent document object deletion");
            }
            return present ? 1 : 0;
        } catch (IOException exception) {
            throw new ObjectStorageException(
                    "Unable to permanently delete document object", exception);
        }
    }

    @Override
    public int permanentlyDeletePrefix(String prefix) {
        if (prefix == null || !prefix.matches(
                "documents/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/")) {
            throw new ObjectStorageException("Invalid permanent-erasure object prefix");
        }
        try (var paths = Files.walk(root)) {
            List<Path> matches = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> relativeKey(path).startsWith(prefix))
                    .toList();
            for (Path match : matches) {
                Files.deleteIfExists(match);
            }
            try (var remaining = Files.walk(root)) {
                if (remaining.filter(Files::isRegularFile)
                        .anyMatch(path -> relativeKey(path).startsWith(prefix))) {
                    throw new ObjectStorageException(
                            "Unable to prove permanent document prefix deletion");
                }
            }
            return matches.size();
        } catch (IOException exception) {
            throw new ObjectStorageException(
                    "Unable to permanently delete document object prefix", exception);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public ObjectKeyPage listKeys(String prefix, String afterKey, int limit) {
        if (prefix == null
                || prefix.isBlank()
                || prefix.startsWith("/")
                || prefix.contains("..")
                || limit < 1) {
            throw new ObjectStorageException("Invalid document object listing request");
        }
        try (var paths = Files.walk(root)) {
            List<String> keys = paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace(path.getFileSystem().getSeparator(), "/"))
                    .filter(key -> key.startsWith(prefix))
                    .filter(key -> afterKey == null || key.compareTo(afterKey) > 0)
                    .sorted(Comparator.naturalOrder())
                    .limit((long) limit + 1)
                    .toList();
            boolean hasMore = keys.size() > limit;
            List<String> page = hasMore ? keys.subList(0, limit) : keys;
            String nextAfterKey = hasMore ? page.get(page.size() - 1) : null;
            return new ObjectKeyPage(page, nextAfterKey);
        } catch (IOException exception) {
            throw new ObjectStorageException("Unable to list document objects", exception);
        }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..")) {
            throw new ObjectStorageException("Invalid document object key");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new ObjectStorageException("Invalid document object key");
        }
        return resolved;
    }

    private String relativeKey(Path path) {
        return root.relativize(path)
                .toString()
                .replace(path.getFileSystem().getSeparator(), "/");
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The original storage failure remains the actionable error.
        }
    }

    private void requireSameContent(Path target, byte[] content) throws IOException {
        if (!Arrays.equals(Files.readAllBytes(target), content)) {
            throw new ObjectStorageException("Document object key collision");
        }
    }
}
