package com.jobseekercopilot.documentstore.storage;

import java.util.UUID;

public interface PermanentErasureJournal {

    PermanentErasureJournalEntry writeOrVerify(
            UUID operationId, byte[] canonicalContent, String contentSha256);

    PermanentErasureJournalEntry read(UUID operationId, String versionId);
}
