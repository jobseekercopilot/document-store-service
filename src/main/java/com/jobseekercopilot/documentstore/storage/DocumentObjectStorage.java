package com.jobseekercopilot.documentstore.storage;

public interface DocumentObjectStorage {
    void checkAvailability();

    void put(String key, byte[] content, String contentType, String sha256);

    byte[] get(String key);

    void delete(String key);

    int permanentlyDeleteKey(String key);

    int permanentlyDeletePrefix(String prefix);

    boolean exists(String key);

    ObjectKeyPage listKeys(String prefix, String afterKey, int limit);
}
