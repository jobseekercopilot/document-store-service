package com.jobseekercopilot.documentstore.storage;

public interface DocumentObjectStorage {
    void put(String key, byte[] content, String contentType, String sha256);

    byte[] get(String key);

    void delete(String key);

    boolean exists(String key);

    ObjectKeyPage listKeys(String prefix, String afterKey, int limit);
}
