package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentstore.TestDocumentFiles;
import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.observability.DocumentStoreMetrics;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import com.jobseekercopilot.documentstore.storage.ObjectStorageException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentFileServiceFailureTest {

    @Test
    void storageFailureCannotDeactivateOrReplaceTheCurrentFileMetadata() {
        String owner = "partial-failure-owner";
        UUID documentId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        byte[] content = TestDocumentFiles.validPdf();
        var files = mock(ExportedDocumentFileRepository.class);
        var documents = mock(GeneratedDocumentRepository.class);
        var storage = mock(DocumentObjectStorage.class);
        var validator = mock(DocumentFileValidator.class);
        var locks = mock(DocumentOperationLock.class);
        var journal = mock(DocumentStorageOperationJournal.class);
        when(documents.findByIdAndUserId(documentId, owner))
                .thenReturn(Optional.of(GeneratedDocument.builder()
                        .id(documentId)
                        .userId(owner)
                        .build()));
        when(validator.decodeAndValidateGenerated(
                        eq(FileType.PDF), anyString(), anyString(), anyString()))
                .thenReturn(content);
        when(validator.safeFileName(fileId, FileType.PDF)).thenReturn("document.pdf");
        when(validator.canonicalMimeType(FileType.PDF)).thenReturn("application/pdf");
        when(files.findFirstByGeneratedDocumentIdAndFileTypeOrderByVersionDesc(
                        documentId, FileType.PDF))
                .thenReturn(Optional.empty());
        when(journal.highestReservedVersion(documentId, FileType.PDF)).thenReturn(0);
        when(journal.prepare(
                        eq(owner),
                        eq(documentId),
                        eq(FileType.PDF),
                        eq(1),
                        eq("storage-failure"),
                        anyString()))
                .thenReturn(new StorageOperationReservation(
                        fileId,
                        1,
                        "documents/" + documentId + "/files/" + fileId + "/v1"));
        doThrow(new ObjectStorageException("injected object-store outage"))
                .when(storage)
                .put(anyString(), eq(content), eq("application/pdf"), anyString());
        var service = new DocumentFileService(
                files,
                documents,
                storage,
                validator,
                locks,
                new DocumentStoreMetrics(new SimpleMeterRegistry()),
                journal,
                mock(DocumentActivityService.class));
        var request = CreateDocumentFileRequest.builder()
                .generatedDocumentId(documentId)
                .fileType(FileType.PDF)
                .fileName("document.pdf")
                .mimeType("application/pdf")
                .fileContentBase64(Base64.getEncoder().encodeToString(content))
                .build();

        assertThatThrownBy(() ->
                        service.createDocumentFile(owner, request, "storage-failure"))
                .isInstanceOf(ObjectStorageException.class);

        verify(files, never())
                .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndFileTypeAndActiveTrue(
                        documentId, owner, FileType.PDF);
        verify(files, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }
}
