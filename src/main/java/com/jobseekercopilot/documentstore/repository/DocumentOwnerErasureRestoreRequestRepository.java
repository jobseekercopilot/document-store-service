package com.jobseekercopilot.documentstore.repository;

import com.jobseekercopilot.documentstore.entity.DocumentOwnerErasureRestoreRequest;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentOwnerErasureRestoreRequestRepository
        extends JpaRepository<DocumentOwnerErasureRestoreRequest, UUID> {

    Optional<DocumentOwnerErasureRestoreRequest> findByOperationId(UUID operationId);

    boolean existsByOwnerFingerprintIn(Collection<String> ownerFingerprints);

    @Query("select distinct request.fingerprintKeyVerifier "
            + "from DocumentOwnerErasureRestoreRequest request")
    List<String> findDistinctFingerprintKeyVerifiers();

    List<DocumentOwnerErasureRestoreRequest> findAllByOrderByUpdatedAtAscRestoreReplayIdAsc(
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from DocumentOwnerErasureRestoreRequest request "
            + "where request.operationId = :operationId")
    Optional<DocumentOwnerErasureRestoreRequest> lockByOperationId(
            @Param("operationId") UUID operationId);
}
