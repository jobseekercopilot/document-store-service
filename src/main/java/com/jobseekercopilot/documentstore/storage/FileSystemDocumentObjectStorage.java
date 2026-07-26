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
