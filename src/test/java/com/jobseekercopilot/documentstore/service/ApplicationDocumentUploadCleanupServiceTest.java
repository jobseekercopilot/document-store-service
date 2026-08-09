package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.config.DocumentUploadProperties;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUpload;
import com.jobseekercopilot.documentstore.entity.ApplicationDocumentUploadState;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.ApplicationDocumentUploadRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ApplicationDocumentUploadCleanupServiceTest {

    @Mock
    private ApplicationDocumentUploadRepository repository;

    @Mock
    private DocumentObjectStorage objectStorage;

    private ApplicationDocumentUploadCleanupService service;

    @BeforeEach
    void setUp() {
        DocumentUploadProperties properties = new DocumentUploadProperties();
        service = new ApplicationDocumentUploadCleanupService(
                repository,
                objectStorage,
                properties,
                new DocumentStoreMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void staleProcessingUploadFailsClosedAndRemovesQuarantineObject() {
        ApplicationDocumentUpload upload = upload();
        when(repository
                .findByStateInAndUpdatedAtBeforeAndQuarantineKeyIsNotNullOrderByUpdatedAtAsc(
                        anyCollection(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(upload));
        when(repository.saveAndFlush(upload)).thenReturn(upload);

        assertThat(service.cleanup()).isEqualTo(1);

        verify(objectStorage).delete("quarantine/application-uploads/test");
        assertThat(upload.getState()).isEqualTo(ApplicationDocumentUploadState.FAILED);
        assertThat(upload.getFailureCode()).isEqualTo("PROCESSING_TIMEOUT");
        assertThat(upload.getQuarantineKey()).isNull();
    }

    @Test
    void failedCleanupLeavesQuarantineReferenceForReconciliationRetry() {
        ApplicationDocumentUpload upload = upload();
        when(repository
                .findByStateInAndUpdatedAtBeforeAndQuarantineKeyIsNotNullOrderByUpdatedAtAsc(
                        anyCollection(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(upload));
        doThrow(new IllegalStateException("private storage detail"))
                .when(objectStorage)
                .delete(anyString());

        assertThat(service.cleanup()).isZero();
        assertThat(upload.getQuarantineKey())
                .isEqualTo("quarantine/application-uploads/test");
    }

    private ApplicationDocumentUpload upload() {
        return ApplicationDocumentUpload.builder()
                .id(UUID.randomUUID())
                .ownerId("owner-private")
                .state(ApplicationDocumentUploadState.SCANNING)
                .quarantineKey("quarantine/application-uploads/test")
                .build();
    }
}
