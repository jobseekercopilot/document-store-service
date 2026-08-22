package com.jobseekercopilot.documentstore.storage;

import java.util.UUID;

public class UnavailablePermanentErasureJournal implements PermanentErasureJournal {

    @Override
    public PermanentErasureJournalEntry writeOrVerify(
            UUID operationId, byte[] canonicalContent, String contentSha256) {
        throw new ObjectStorageException(
                "Permanent-erasure recovery journaling is unavailable");
    }

    @Override
    public PermanentErasureJournalEntry read(UUID operationId, String versionId) {
        throw new ObjectStorageException(
                "Permanent-erasure recovery journaling is unavailable");
    }
}
