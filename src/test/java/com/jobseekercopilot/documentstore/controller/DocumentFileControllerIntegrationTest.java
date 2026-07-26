package com.jobseekercopilot.documentstore.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
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
        byte[] content = "docx-bytes".getBytes(StandardCharsets.UTF_8);

        String response = mockMvc.perform(post("/api/v1/document-files")
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fileRequest(document.getId(), "cv.docx", content))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.generatedDocumentId").value(document.getId().toString()))
                .andExpect(jsonPath("$.fileType").value("DOCX"))
                .andExpect(jsonPath("$.fileName").value("cv.docx"))
                .andExpect(jsonPath("$.mimeType").value(DOCX_MIME_TYPE))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertFalse(json.has("fileContentBase64"));
        var stored = fileRepository.findById(UUID.fromString(json.get("id").asText()))
                .orElseThrow();
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
        UUID fileId = createFile(saveDocument().getId(), "cv.docx", "docx-bytes".getBytes(StandardCharsets.UTF_8));

        String response = mockMvc.perform(get("/api/v1/document-files/{id}", fileId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId.toString()))
                .andExpect(jsonPath("$.fileName").value("cv.docx"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(objectMapper.readTree(response).has("fileContentBase64"));
    }

    @Test
    void downloadDocumentFile_ShouldReturnSavedBytesAndDownloadHeaders() throws Exception {
        byte[] content = "pdf-bytes".getBytes(StandardCharsets.UTF_8);
        UUID fileId = createFile(saveDocument().getId(), "cv.pdf", content);

        byte[] actual = mockMvc.perform(get("/api/v1/document-files/{id}/download", fileId)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, PDF_MIME_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cv.pdf\""))
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
                "expected-pdf".getBytes(StandardCharsets.UTF_8));
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
    void deleteDocument_ShouldDeleteObjectAndMetadata() throws Exception {
        GeneratedDocument document = saveDocument();
        UUID fileId = createFile(
                document.getId(),
                "cv.pdf",
                "delete-me".getBytes(StandardCharsets.UTF_8));
        String key = fileRepository.findById(fileId).orElseThrow().getStorageKey();

        mockMvc.perform(delete("/api/v1/documents/{id}", document.getId())
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isNoContent());

        assertFalse(objectStorage.exists(key));
        assertTrue(fileRepository.findById(fileId).isEmpty());
        assertTrue(documentRepository.findById(document.getId()).isEmpty());
    }

    @Test
    void getFilesForDocument_ShouldReturnLinkedFileMetadata() throws Exception {
        GeneratedDocument document = saveDocument();
        createFile(document.getId(), "cv.docx", "docx-bytes".getBytes(StandardCharsets.UTF_8));
        createFile(document.getId(), "cv.pdf", "pdf-bytes".getBytes(StandardCharsets.UTF_8));

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
        UUID previousFileId = createFile(document.getId(), "cv.docx", "generated-docx".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile replacement = new MockMultipartFile(
                "file",
                "cv-edited.docx",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                minimalDocx());

        mockMvc.perform(multipart("/api/v1/documents/{generatedDocumentId}/files/upload", document.getId())
                        .file(replacement)
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123")
                        .param("fileType", "DOCX")
                        .param("source", "USER_UPLOADED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.generatedDocumentId").value(document.getId().toString()))
                .andExpect(jsonPath("$.fileType").value("DOCX"))
                .andExpect(jsonPath("$.source").value("USER_UPLOADED"))
                .andExpect(jsonPath("$.active").value(true));

        assertFalse(fileRepository.findById(previousFileId).orElseThrow().isActive());
        mockMvc.perform(get("/api/v1/documents/{generatedDocumentId}/files/latest", document.getId())
                        .header(DocumentServiceIdentityFilter.SERVICE_HEADER, PRODUCER_TOKEN)
                        .header(DocumentOwnerResolver.OWNER_HEADER, "user-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].fileName").value("cv-edited.docx"))
                .andExpect(jsonPath("$[0].active").value(true));
        assertTrue(fileRepository
                .findByGeneratedDocumentIdAndGeneratedDocument_UserIdAndFileTypeAndActiveTrue(
                        document.getId(),
                        "user-123",
                        FileType.DOCX)
                .stream()
                .allMatch(file -> file.getFileName().equals("cv-edited.docx")));
    }

    private byte[] minimalDocx() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>Edited CV</w:t></w:r></w:p></w:body>
                    </w:document>
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
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
                                "content".getBytes(StandardCharsets.UTF_8)))))
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
