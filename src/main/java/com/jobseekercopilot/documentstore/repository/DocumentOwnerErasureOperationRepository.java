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

    Optional<DocumentOwnerErasureOperation> findByOwnerFingerprint(String ownerFingerprint);

    boolean existsByOwnerFingerprint(String ownerFingerprint);

    boolean existsByFingerprintKeyVerifierNot(String fingerprintKeyVerifier);

    long countByStateIn(Collection<DocumentOwnerErasureState> states);

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
