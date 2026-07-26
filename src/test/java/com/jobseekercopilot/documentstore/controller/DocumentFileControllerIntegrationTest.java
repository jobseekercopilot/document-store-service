package com.jobseekercopilot.documentstore.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentstore.TestDocumentFiles;
import com.jobseekercopilot.documentstore.dto.CreateDocumentFileRequest;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.entity.ObjectStorageStatus;
import com.jobseekercopilot.documentstore.entity.GeneratedDocument;
import com.jobseekercopilot.documentstore.repository.ExportedDocumentFileRepository;
import com.jobseekercopilot.documentstore.repository.GeneratedDocumentRepository;
import com.jobseekercopilot.documentstore.security.DocumentOwnerResolver;
import com.jobseekercopilot.documentstore.security.DocumentServiceIdentityFilter;
import com.jobseekercopilot.documentstore.storage.DocumentObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "document-store.validation.maximum-file-bytes=2048")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class DocumentFileControllerIntegrationTest {

    private static final String DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final String PRODUCER_TOKEN =
            "test-only-document-producer-token-32-bytes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GeneratedDocumentRepository documentRepository;

    @Autowired
    private ExportedDocumentFileRepository fileRepository;

    @Autowired
    private DocumentObjectStorage objectStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createDocumentFile_ShouldSaveBytesAndReturnMetadataOnly() throws Exception {
        GeneratedDocument document = saveDocument();
        byte[] content = TestDocumentFiles.validDocx();

        String response = mockMvc.perform(post("/api/v1/document-files")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fileRequest(document.getId(), "cv.docx", content))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.generatedDocumentId").value(document.getId().toString()))
                .andExpect(jsonPath("$.fileType").value("DOCX"))
                .andExpect(jsonPath("$.mimeType").value(DOCX_MIME_TYPE))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertFalse(json.has("fileContentBase64"));
        var stored = fileRepository.findById(UUID.fromString(json.get("id").asText()))
                .orElseThrow();
        assertEquals(
                "document-" + stored.getId() + ".docx",
                json.get("fileName").asText());
        assertEquals(json.get("fileName").asText(), stored.getFileName());
        assertTrue(objectStorage.exists(stored.getStorageKey()));
        assertArrayEquals(content, objectStorage.get(stored.getStorageKey()));
        assertTrue(json.get("contentSize").asLong() == content.length);
        assertTrue(json.get("contentSha256").asText().length() == 64);
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT file_content IS NULL FROM exported_document_files WHERE id = ?",
                Boolean.class,
                stored.getId()));
    }

    @Test
    void getDocumentFileMetadata_WhenExists_ShouldReturnMetadataOnly() throws Exception {
        UUID fileId = createFile(
                saveDocument().getId(),
                "cv.docx",
                TestDocumentFiles.validDocx());

        String response = mockMvc.perform(get("/api/v1/document-files/{id}", fileId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId.toString()))
                .andExpect(jsonPath("$.fileName")
                        .value("document-" + fileId + ".docx"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(objectMapper.readTree(response).has("fileContentBase64"));
    }

    @Test
    void downloadDocumentFile_ShouldReturnSavedBytesAndDownloadHeaders() throws Exception {
        byte[] content = TestDocumentFiles.validPdf();
        UUID fileId = createFile(saveDocument().getId(), "cv.pdf", content);

        byte[] actual = mockMvc.perform(get("/api/v1/document-files/{id}/download", fileId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, PDF_MIME_TYPE))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"document-" + fileId + ".pdf\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        "private, no-store, max-age=0"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().longValue(
                        HttpHeaders.CONTENT_LENGTH,
                        content.length))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertArrayEquals(content, actual);
    }

    @Test
    void downloadDocumentFile_WhenObjectIntegrityFails_ShouldReturn503AndQuarantineMetadata()
            throws Exception {
        UUID fileId = createFile(
                saveDocument().getId(),
                "cv.pdf",
                TestDocumentFiles.validPdf());
        var metadata = fileRepository.findById(fileId).orElseThrow();
        objectStorage.delete(metadata.getStorageKey());
        byte[] corrupt = "corrupt-pdf".getBytes(StandardCharsets.UTF_8);
        objectStorage.put(
                metadata.getStorageKey(),
                corrupt,
                PDF_MIME_TYPE,
                com.jobseekercopilot.documentstore.storage.ObjectIntegrity.sha256(corrupt));

        mockMvc.perform(get("/api/v1/document-files/{id}/download", fileId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Document file storage is temporarily unavailable."));

        assertTrue(fileRepository.findById(fileId).orElseThrow().getStorageStatus()
                == ObjectStorageStatus.UNAVAILABLE);
    }

    @Test
    void generatedFileValidationRejectsUnsafeMetadataAndContentBeforePersistence()
            throws Exception {
        GeneratedDocument document = saveDocument();
        CreateDocumentFileRequest unsafeName =
                fileRequest(document.getId(), "../cv.pdf", TestDocumentFiles.validPdf());
        CreateDocumentFileRequest spoofedMime =
                fileRequest(document.getId(), "cv.pdf", TestDocumentFiles.validPdf());
        spoofedMime.setMimeType(MediaType.IMAGE_PNG_VALUE);
        CreateDocumentFileRequest spoofedContent =
                fileRequest(
                        document.getId(),
                        "cv.pdf",
                        "not-a-pdf".getBytes(StandardCharsets.UTF_8));

        for (CreateDocumentFileRequest request :
                new CreateDocumentFileRequest[] {
                    unsafeName,
                    spoofedMime,
                    spoofedContent
                }) {
            mockMvc.perform(post("/api/v1/document-files")
                            .header(
                                    DocumentServiceIdentityFilter.SERVICE_HEADER,
                                    PRODUCER_TOKEN)
                            .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        String unsupportedType = objectMapper.writeValueAsString(
                        fileRequest(document.getId(), "cv.pdf", TestDocumentFiles.validPdf()))
                .replace("\"PDF\"", "\"TXT\"");
        mockMvc.perform(post("/api/v1/document-files")
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unsupportedType))
                .andExpect(status().isBadRequest());

        assertEquals(0, fileRepository.count());
    }

    @Test
    void oversizedGeneratedFileReturns413BeforePersistence() throws Exception {
        GeneratedDocument document = saveDocument();
        byte[] content = new byte[2049];

        mockMvc.perform(post("/api/v1/document-files")
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                fileRequest(document.getId(), "cv.pdf", content))))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message")
                        .value("File exceeds the private beta size limit"));

        assertEquals(0, fileRepository.count());
    }

    @Test
    void userReplacementPdfIsRejectedWithoutChangingTheCurrentDocx()
            throws Exception {
        GeneratedDocument document = saveDocument();
        UUID currentFileId = createFile(
                document.getId(),
                "cv.docx",
                TestDocumentFiles.validDocx());
        MockMultipartFile replacement = new MockMultipartFile(
                "file",
                "replacement.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                TestDocumentFiles.validPdf());

        mockMvc.perform(multipart(
                                "/api/v1/documents/{generatedDocumentId}/files/upload",
                                document.getId())
                        .file(replacement)
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .param("fileType", "PDF")
                        .param("source", "USER_UPLOADED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Private beta replacement uploads support DOCX only"));

        assertEquals(1, fileRepository.count());
        assertTrue(fileRepository.findById(currentFileId).orElseThrow().isActive());
    }

    @Test
    void oversizedMultipartFileReturns413BeforePersistence() throws Exception {
        GeneratedDocument document = saveDocument();
        MockMultipartFile oversized = new MockMultipartFile(
                "file",
                "replacement.docx",
                DOCX_MIME_TYPE,
                new byte[2049]);

        mockMvc.perform(multipart(
                                "/api/v1/documents/{generatedDocumentId}/files/upload",
                                document.getId())
                        .file(oversized)
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .param("fileType", "DOCX")
                        .param("source", "USER_UPLOADED"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message")
                        .value("File exceeds the private beta size limit"));

        assertEquals(0, fileRepository.count());
    }

    @Test
    void storedFileThatPassesChecksumButFailsSafetyValidationIsQuarantined()
            throws Exception {
        UUID fileId = createFile(
                saveDocument().getId(),
                "cv.pdf",
                TestDocumentFiles.validPdf());
        var metadata = fileRepository.findById(fileId).orElseThrow();
        byte[] activeContent =
                "%PDF-1.7\n/JavaScript\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
        objectStorage.delete(metadata.getStorageKey());
        objectStorage.put(
                metadata.getStorageKey(),
                activeContent,
                PDF_MIME_TYPE,
                com.jobseekercopilot.documentstore.storage.ObjectIntegrity.sha256(
                        activeContent));
        metadata.setContentSize(activeContent.length);
        metadata.setContentSha256(
                com.jobseekercopilot.documentstore.storage.ObjectIntegrity.sha256(
                        activeContent));
        fileRepository.saveAndFlush(metadata);

        mockMvc.perform(get("/api/v1/document-files/{id}/download", fileId)
                        .header(
                                DocumentServiceIdentityFilter.SERVICE_HEADER,
                                PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Document file storage is temporarily unavailable."));

        assertEquals(
                ObjectStorageStatus.UNAVAILABLE,
                fileRepository.findById(fileId).orElseThrow().getStorageStatus());
    }

    @Test
    void deleteDocument_ShouldRetainObjectAndMetadataForRecovery() throws Exception {
        GeneratedDocument document = saveDocument();
        UUID fileId = createFile(
                document.getId(),
                "cv.pdf",
                TestDocumentFiles.validPdf());
        String key = fileRepository.findById(fileId).orElseThrow().getStorageKey();

        mockMvc.perform(delete("/api/v1/documents/{id}", document.getId())
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isNoContent());

        assertTrue(objectStorage.exists(key));
        assertTrue(fileRepository.findById(fileId).isPresent());
        assertEquals(
                com.jobseekercopilot.documentstore.entity.DocumentRetentionState.DELETED,
                documentRepository.findById(document.getId()).orElseThrow().getRetentionState());
    }

    @Test
    void getFilesForDocument_ShouldReturnLinkedFileMetadata() throws Exception {
        GeneratedDocument document = saveDocument();
        createFile(document.getId(), "cv.docx", TestDocumentFiles.validDocx());
        createFile(document.getId(), "cv.pdf", TestDocumentFiles.validPdf());

        mockMvc.perform(get("/api/v1/documents/{generatedDocumentId}/files", document.getId())
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].generatedDocumentId").value(document.getId().toString()));
    }

    @Test
    void uploadReplacementFile_ShouldDeactivatePreviousFileAndReturnLatestActiveFile() throws Exception {
        GeneratedDocument document = saveDocument();
        UUID previousFileId = createFile(
                document.getId(),
                "cv.docx",
                TestDocumentFiles.validDocx());
        MockMultipartFile replacement = new MockMultipartFile(
                "file",
                "cv-edited.docx",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                TestDocumentFiles.validDocx());

        String uploadResponse = mockMvc.perform(multipart(
                                "/api/v1/documents/{generatedDocumentId}/files/upload",
                                document.getId())
                        .file(replacement)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .param("fileType", "DOCX")
                        .param("source", "USER_UPLOADED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.generatedDocumentId").value(document.getId().toString()))
                .andExpect(jsonPath("$.fileType").value("DOCX"))
                .andExpect(jsonPath("$.source").value("USER_UPLOADED"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID replacementFileId = UUID.fromString(
                objectMapper.readTree(uploadResponse).path("id").asText());

        assertFalse(fileRepository.findById(previousFileId).orElseThrow().isActive());
        mockMvc.perform(get("/api/v1/documents/{generatedDocumentId}/files/latest", document.getId())
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].fileName")
                        .value("document-" + replacementFileId + ".docx"))
                .andExpect(jsonPath("$[0].active").value(true));
        assertTrue(fileRepository
                .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndFileTypeAndActiveTrue(
                        document.getId(),
                        "user-123",
                        FileType.DOCX)
                .stream()
                .allMatch(file -> file.getFileName()
                .equals("document-" + replacementFileId + ".docx")));
    }

    @Test
    void createFile_IdempotencyKeyReplaysAndRejectsDifferentBytes() throws Exception {
        GeneratedDocument document = saveDocument();
        byte[] firstPdf = TestDocumentFiles.validPdf();
        CreateDocumentFileRequest request =
                fileRequest(document.getId(), "cv.pdf", firstPdf);

        String first = mockMvc.perform(post("/api/v1/document-files")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .header("Idempotency-Key", "export-document-pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String replay = mockMvc.perform(post("/api/v1/document-files")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .header("Idempotency-Key", "export-document-pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertEquals(
                objectMapper.readTree(first).path("id").asText(),
                objectMapper.readTree(replay).path("id").asText());

        byte[] differentPdf =
                (new String(firstPdf, StandardCharsets.ISO_8859_1) + "\n")
                        .getBytes(StandardCharsets.ISO_8859_1);
        request.setFileContentBase64(Base64.getEncoder().encodeToString(differentPdf));
        mockMvc.perform(post("/api/v1/document-files")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .header("Idempotency-Key", "export-document-pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Idempotency-Key was already used for a different document file operation."));
        assertEquals(
                1,
                fileRepository
                        .findByGeneratedDocumentIdOrderByCreatedAtDesc(document.getId())
                        .size());
    }

    @Test
    void activateFileVersion_ShouldRestoreRetainedPreviousVersion() throws Exception {
        GeneratedDocument document = saveDocument();
        UUID firstId = createFile(
                document.getId(), "cv.docx", TestDocumentFiles.validDocx());
        UUID secondId = createFile(
                document.getId(), "cv.docx", TestDocumentFiles.validDocx());

        assertFalse(fileRepository.findById(firstId).orElseThrow().isActive());
        assertTrue(fileRepository.findById(secondId).orElseThrow().isActive());
        mockMvc.perform(patch("/api/v1/document-files/{id}/active", firstId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.active").value(true));

        assertTrue(fileRepository.findById(firstId).orElseThrow().isActive());
        assertFalse(fileRepository.findById(secondId).orElseThrow().isActive());
    }

    @Test
    void createDocumentFile_WhenGeneratedDocumentMissing_ShouldReturn404() throws Exception {
        mockMvc.perform(post("/api/v1/document-files")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fileRequest(
                                UUID.randomUUID(),
                                "missing.docx",
                                TestDocumentFiles.validDocx()))))
                .andExpect(status().isNotFound());
    }

    private GeneratedDocument saveDocument() {
        return documentRepository.save(GeneratedDocument.builder()
                .userId("user-123")
                .jobId("job-456")
                .documentType(DocumentType.CV)
                .title("Java Developer CV")
                .content("Generated CV content...")
                .build());
    }

    private UUID createFile(UUID generatedDocumentId, String fileName, byte[] content) throws Exception {
        String response = mockMvc.perform(post("/api/v1/document-files")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fileRequest(generatedDocumentId, fileName, content))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private CreateDocumentFileRequest fileRequest(UUID generatedDocumentId, String fileName, byte[] content) {
        FileType fileType = fileName.toLowerCase().endsWith(".pdf") ? FileType.PDF : FileType.DOCX;
        return CreateDocumentFileRequest.builder()
                .generatedDocumentId(generatedDocumentId)
                .fileType(fileType)
                .fileName(fileName)
                .mimeType(fileType == FileType.PDF ? PDF_MIME_TYPE : DOCX_MIME_TYPE)
                .fileContentBase64(Base64.getEncoder().encodeToString(content))
                .build();
    }
}
