package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureOperation;
import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureState;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentOwnerErasureOperationRepository
        extends JpaRepository<DocumentOwnerErasureOperation, UUID> {

    Optional<DocumentOwnerErasureOperation> findByOwnerFingerprintIn(
            Collection<String> ownerFingerprints);

    boolean existsByOwnerFingerprintIn(Collection<String> ownerFingerprints);

    @Query("select distinct operation.fingerprintKeyVerifier "
            + "from DocumentOwnerErasureOperation operation")
    List<String> findDistinctFingerprintKeyVerifiers();

    @Query("select count(operation) from DocumentOwnerErasureOperation operation "
            + "where operation.state <> com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureState.JOURNAL_PENDING "
            + "and (operation.journalRequired = false "
            + "or operation.journalContentSha256 is null)")
    long countAdvancedOperationsWithoutJournalEvidence();

    long countByStateIn(Collection<DocumentOwnerErasureState> states);

    long countByStateAndBackupRetentionUntilLessThanEqual(
            DocumentOwnerErasureState state,
            LocalDateTime cutoff);

    List<DocumentOwnerErasureOperation> findByStateInOrderByUpdatedAtAscOperationIdAsc(
            Collection<DocumentOwnerErasureState> states,
            Pageable pageable);

    @Query("select operation from DocumentOwnerErasureOperation operation "
            + "where operation.state = :state "
            + "and operation.backupRetentionUntil <= :cutoff "
            + "and operation.backupExpiryEvidenceSha256 is not null "
            + "order by operation.backupRetentionUntil asc, operation.operationId asc")
    List<DocumentOwnerErasureOperation> findDueAttestedBackupRetention(
            @Param("state") DocumentOwnerErasureState state,
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operation from DocumentOwnerErasureOperation operation "
            + "where operation.operationId = :operationId")
    Optional<DocumentOwnerErasureOperation> lockByOperationId(
            @Param("operationId") UUID operationId);
}
