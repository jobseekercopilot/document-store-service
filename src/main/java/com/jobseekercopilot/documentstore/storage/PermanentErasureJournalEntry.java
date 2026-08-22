package com.jobseekercopilot.documentstore.storage;

public record PermanentErasureJournalEntry(
        String objectKey,
        String objectVersion,
        String contentSha256,
        byte[] canonicalContent) {

    public PermanentErasureJournalEntry {
        canonicalContent = canonicalContent.clone();
    }

    @Override
    public byte[] canonicalContent() {
        return canonicalContent.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PermanentErasureJournalEntry entry
                        && java.util.Objects.equals(objectKey, entry.objectKey)
                        && java.util.Objects.equals(objectVersion, entry.objectVersion)
                        && java.util.Objects.equals(contentSha256, entry.contentSha256)
                        && java.util.Arrays.equals(
                                canonicalContent, entry.canonicalContent));
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(
                objectKey, objectVersion, contentSha256);
        return 31 * result + java.util.Arrays.hashCode(canonicalContent);
    }
}
