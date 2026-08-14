package com.jobseekercopilot.documentstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobseekercopilot.documentstore.TestDocumentFiles;
import com.jobseekercopilot.documentstore.config.DocumentFileValidationProperties;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.DocumentFileTooLargeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentFileValidatorTest {

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Override PartName="/word/document.xml"
                ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """;
    private static final String DOCUMENT_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>Synthetic CV</w:t></w:r></w:p></w:body>
            </w:document>
            """;

    private DocumentFileValidationProperties properties;
    private DocumentFileValidator validator;

    @BeforeEach
    void setUp() {
        properties = new DocumentFileValidationProperties();
        validator = new DocumentFileValidator(properties);
    }

    @Test
    void acceptsContentVerifiedGeneratedPdfAndDocx() {
        validator.validateGenerated(
                FileType.PDF,
                "generated.pdf",
                DocumentFileValidator.PDF_MIME_TYPE,
                validPdf());
        validator.validateGenerated(
                FileType.DOCX,
                "generated.docx",
                DocumentFileValidator.DOCX_MIME_TYPE,
                validDocx());
    }

    @Test
    void replacementUploadsAreDocxOnly() {
        assertThatThrownBy(() -> validator.validateUserUpload(
                        FileType.PDF,
                        "replacement.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        validPdf()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Private beta replacement uploads support DOCX only");

        validator.validateUserUpload(
                FileType.DOCX,
                "replacement.docx",
                MediaTypes.OCTET_STREAM,
                validDocx());
    }

    @Test
    void decodedAndBase64SizesFailBeforeDecodeOrPersistence() {
        properties.setMaximumFileBytes(32);
        assertThatThrownBy(() -> validator.validateDeclaredSize(33))
                .isInstanceOf(DocumentFileTooLargeException.class);
        String oversized = Base64.getEncoder().encodeToString(new byte[33]);
        assertThatThrownBy(() -> validator.decodeAndValidateGenerated(
                        FileType.PDF,
                        "generated.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        oversized))
                .isInstanceOf(DocumentFileTooLargeException.class);

        assertThatThrownBy(() -> validator.decodeAndValidateGenerated(
                        FileType.PDF,
                        "generated.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        "not!base64"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File content must be valid Base64");
    }

    @Test
    void fileNamesAreUntrustedAndOnlySafeGeneratedNamesAreReturned() {
        for (String unsafe : new String[] {
            null,
            "../cv.docx",
            "folder/cv.docx",
            "folder\\cv.docx",
            "cv\r\nattachment.docx",
            " cv.docx",
            "cv.docx ",
            "cv:alternate.docx",
            "cv\u202Ecod.docx",
            "cv.pdf"
        }) {
            assertThatThrownBy(() -> validator.validateUserUpload(
                            FileType.DOCX,
                            unsafe,
                            DocumentFileValidator.DOCX_MIME_TYPE,
                            validDocx()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        UUID fileId = UUID.randomUUID();
        assertThat(validator.safeFileName(fileId, FileType.DOCX))
                .isEqualTo("document-" + fileId + ".docx")
                .doesNotContain("cv");
    }

    @Test
    void spoofedMimeAndSignatureMismatchAreRejected() {
        assertThatThrownBy(() -> validator.validateGenerated(
                        null,
                        "generated.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        validPdf()))
                .hasMessage("Only DOCX and PDF files are supported");

        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.PDF,
                        "generated.pdf",
                        "image/png",
                        validPdf()))
                .hasMessage("Uploaded file MIME type does not match PDF");
        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.PDF,
                        "generated.pdf",
                        MediaTypes.OCTET_STREAM,
                        validPdf()))
                .hasMessage("Uploaded file MIME type does not match PDF");

        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.PDF,
                        "generated.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        "not a pdf".getBytes(StandardCharsets.UTF_8)))
                .hasMessage("File content does not match PDF");
    }

    @Test
    void corruptEncryptedAndActivePdfContentIsRejected() {
        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.PDF,
                        "generated.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        "%PDF-1.7\nmissing trailer".getBytes(StandardCharsets.ISO_8859_1)))
                .hasMessage("PDF file is corrupt or incomplete");

        for (byte[] content : new byte[][] {
            encryptedPdf(),
            pdfWithLinkAction(new PDActionJavaScript("app.alert('unsafe')")),
            pdfWithLinkAction(launchAction()),
            pdfWithMutation(document -> document.getDocumentCatalog()
                    .setAcroForm(new PDAcroForm(document))),
            pdfWithMutation(document -> {
                COSDictionary names = new COSDictionary();
                names.setItem(COSName.EMBEDDED_FILES, new COSDictionary());
                document.getDocumentCatalog().getCOSObject()
                        .setItem(COSName.NAMES, names);
            })
        }) {
            assertThatThrownBy(() -> validator.validateGenerated(
                            FileType.PDF,
                            "generated.pdf",
                            DocumentFileValidator.PDF_MIME_TYPE,
                            content))
                    .hasMessage(
                            "PDF active, encrypted or external content is not supported");
        }
    }

    @Test
    void pdfCredentialFreeHttpsUriLinksAreAcceptedForGeneratedAndStoredBytes() {
        byte[] linkedPdf = TestDocumentFiles.pdfWithUriLinks(
                "https://github.com/jobseekercopilot",
                "https://drive.google.com/drive/folders/example?usp=sharing#portfolio");

        validator.validateGenerated(
                FileType.PDF,
                "generated.pdf",
                DocumentFileValidator.PDF_MIME_TYPE,
                linkedPdf);
        validator.validateStored(FileType.PDF, linkedPdf);
    }

    @Test
    void pdfBenignAaaaaaFontNameDoesNotImpersonateAdditionalActions() {
        byte[] pdf = TestDocumentFiles.pdfWithBenignAaaaaaFontResource();
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).contains("/AAAAAA+");

        validator.validateGenerated(
                FileType.PDF,
                "generated.pdf",
                DocumentFileValidator.PDF_MIME_TYPE,
                pdf);
        validator.validateStored(FileType.PDF, pdf);
    }

    @Test
    void pdfRealAdditionalActionDictionaryRemainsRejected() {
        PDActionURI action = uriAction("https://example.invalid/profile");
        byte[] pdf = pdfWithLinkAction(action, link -> {
            COSDictionary additionalActions = new COSDictionary();
            additionalActions.setItem(
                    COSName.getPDFName("E"),
                    new PDActionJavaScript("app.alert('unsafe')"));
            link.getCOSObject().setItem(COSName.AA, additionalActions);
        });

        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.PDF,
                        "generated.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        pdf))
                .hasMessage("PDF active, encrypted or external content is not supported");
        assertThatThrownBy(() -> validator.validateStored(FileType.PDF, pdf))
                .hasMessage("PDF active, encrypted or external content is not supported");
    }

    @Test
    void pdfUnsafeUriTargetsAndNonUriActionsAreRejected() {
        for (String target : new String[] {
            "http://example.invalid/profile",
            "mailto:recruiter@example.invalid",
            "/relative/profile",
            "https:/missing-authority",
            "https://user:password@example.invalid/profile",
            "https://@example.invalid/profile",
            "https://example.invalid/profile%0Ainjected",
            "https://example.invalid/profile\ninjected",
            "https://example.invalid/profile\u202Einjected"
        }) {
            byte[] pdf = TestDocumentFiles.pdfWithUriLinks(target);
            assertThatThrownBy(() -> validator.validateGenerated(
                            FileType.PDF,
                            "generated.pdf",
                            DocumentFileValidator.PDF_MIME_TYPE,
                            pdf))
                    .hasMessage(
                            "PDF active, encrypted or external content is not supported");
        }

        PDActionURI chainedUri = uriAction("https://example.invalid/profile");
        chainedUri.setNext(List.of(new PDActionJavaScript("app.alert('unsafe')")));
        for (byte[] pdf : new byte[][] {
            pdfWithLinkAction(new PDActionJavaScript("app.alert('unsafe')")),
            pdfWithLinkAction(launchAction()),
            pdfWithLinkAction(chainedUri),
            pdfWithLinkAction(null),
            pdfWithTextAnnotation(),
            pdfWithMutation(document -> document.getDocumentCatalog()
                    .setOpenAction(uriAction("https://example.invalid/profile")))
        }) {
            assertThatThrownBy(() -> validator.validateGenerated(
                            FileType.PDF,
                            "generated.pdf",
                            DocumentFileValidator.PDF_MIME_TYPE,
                            pdf))
                    .hasMessage(
                            "PDF active, encrypted or external content is not supported");
        }
    }

    @Test
    void pdfLinkCountAndTargetLengthLimitsAreEnforced() {
        properties.setMaximumPdfLinkAnnotations(1);
        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.PDF,
                        "generated.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        TestDocumentFiles.pdfWithUriLinks(
                                "https://example.invalid/one",
                                "https://example.invalid/two")))
                .hasMessage("PDF contains too many external link annotations");

        properties.setMaximumPdfLinkAnnotations(64);
        properties.setMaximumPdfLinkTargetCharacters(24);
        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.PDF,
                        "generated.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        TestDocumentFiles.pdfWithUriLinks(
                                "https://example.invalid/profile")))
                .hasMessage("PDF active, encrypted or external content is not supported");
    }

    @Test
    void pdfExternalLinksRemainRejectedForReplacementAndApplicationUploads() {
        byte[] linkedPdf = TestDocumentFiles.pdfWithUriLinks(
                "https://github.com/jobseekercopilot");

        assertThatThrownBy(() -> validator.validateUserUpload(
                        FileType.PDF,
                        "replacement.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        linkedPdf))
                .hasMessage("Private beta replacement uploads support DOCX only");
        assertThatThrownBy(() -> validator.validateApplicationUpload(
                        FileType.PDF,
                        "application.pdf",
                        DocumentFileValidator.PDF_MIME_TYPE,
                        linkedPdf))
                .hasMessage("PDF active, encrypted or external content is not supported");
    }

    @Test
    void docxUnsafePathsDuplicatesAndActivePartsAreRejected() {
        assertInvalidDocx(
                entries("../outside.xml", "<x/>"),
                "DOCX contains an unsafe archive path");
        assertInvalidDocx(
                entries("[content_types].xml", CONTENT_TYPES),
                "DOCX contains duplicate archive entries");
        assertInvalidDocx(
                entries("word/vbaProject.bin", "macro"),
                "DOCX active, embedded or encrypted content is not supported");
        assertInvalidDocx(
                entries("word/embeddings/object1.bin", "object"),
                "DOCX active, embedded or encrypted content is not supported");
        assertInvalidDocx(
                entries("EncryptionInfo", "password-protected"),
                "DOCX active, embedded or encrypted content is not supported");
    }

    @Test
    void docxCredentialFreeHttpsHyperlinkRelationshipsAreAccepted() {
        String externalRelationships = """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" TargetMode="External"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                    Target="https://github.com/jobseekercopilot"/>
                  <Relationship Id="rId2" TargetMode="External"
                    Type="http://purl.oclc.org/ooxml/officeDocument/relationships/hyperlink"
                    Target="https://drive.google.com/drive/folders/example?usp=sharing"/>
                </Relationships>
                """;
        byte[] linkedDocx =
                docx(entries("word/_rels/document.xml.rels", externalRelationships));
        validator.validateGenerated(
                FileType.DOCX,
                "generated.docx",
                DocumentFileValidator.DOCX_MIME_TYPE,
                linkedDocx);
        validator.validateStored(FileType.DOCX, linkedDocx);
    }

    @Test
    void docxExternalHyperlinksRemainRejectedForUserAndApplicationUploads() {
        String externalRelationships = """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" TargetMode="External"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                    Target="https://github.com/jobseekercopilot"/>
                </Relationships>
                """;
        byte[] linkedDocx =
                docx(entries("word/_rels/document.xml.rels", externalRelationships));

        assertThatThrownBy(() -> validator.validateUserUpload(
                        FileType.DOCX,
                        "replacement.docx",
                        DocumentFileValidator.DOCX_MIME_TYPE,
                        linkedDocx))
                .hasMessage("DOCX external relationships are not supported");
        assertThatThrownBy(() -> validator.validateApplicationUpload(
                        FileType.DOCX,
                        "application.docx",
                        DocumentFileValidator.DOCX_MIME_TYPE,
                        linkedDocx))
                .hasMessage("DOCX external relationships are not supported");
    }

    @Test
    void docxNonHttpsCredentialedAndNonHyperlinkExternalRelationshipsAreRejected() {
        for (ExternalRelationship relationship : new ExternalRelationship[] {
            new ExternalRelationship(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
                    "http://example.invalid"),
            new ExternalRelationship(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
                    "mailto:recruiter@example.invalid"),
            new ExternalRelationship(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
                    "/relative/profile"),
            new ExternalRelationship(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
                    "https:/missing-authority"),
            new ExternalRelationship(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
                    "https://user:password@example.invalid/profile"),
            new ExternalRelationship(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
                    "https://@example.invalid/profile"),
            new ExternalRelationship(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image",
                    "https://example.invalid/image.png"),
            new ExternalRelationship("", "https://example.invalid")
        }) {
            String externalRelationships = """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" TargetMode="External" Type="%s" Target="%s"/>
                    </Relationships>
                    """.formatted(relationship.type(), relationship.target());
            assertInvalidDocx(
                    entries("word/_rels/document.xml.rels", externalRelationships),
                    "DOCX supports only credential-free HTTPS hyperlink relationships");
        }
    }

    @Test
    void docxImportedContentIsRejected() {

        String importedDocument = """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:altChunk/></w:body>
                </w:document>
                """;
        assertInvalidDocx(
                entries("word/document.xml", importedDocument),
                "DOCX imported or embedded content is not supported");
    }

    @Test
    void docxXmlEntitiesAndMacroContentTypesAreRejected() {
        String entityDocument = """
                <!DOCTYPE document [<!ENTITY external SYSTEM "file:///etc/passwd">]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p>&external;</w:p></w:body>
                </w:document>
                """;
        assertInvalidDocx(
                entries("word/document.xml", entityDocument),
                "DOCX XML content is invalid");

        String macroTypes = CONTENT_TYPES.replace(
                DocumentFileValidator.DOCX_MIME_TYPE + ".main+xml",
                "application/vnd.ms-word.document.macroEnabled.main+xml");
        assertInvalidDocx(
                entries("[Content_Types].xml", macroTypes),
                "DOCX active content is not supported");
    }

    @Test
    void docxEntryCountExpansionAndEntryLimitsAreEnforced() {
        properties.setMaximumDocxEntries(2);
        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.DOCX,
                        "generated.docx",
                        DocumentFileValidator.DOCX_MIME_TYPE,
                        docx(entries("word/styles.xml", "<styles/>"))))
                .hasMessage("DOCX contains too many archive entries");

        setUp();
        properties.setMaximumDocxEntryBytes(32);
        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.DOCX,
                        "generated.docx",
                        DocumentFileValidator.DOCX_MIME_TYPE,
                        validDocx()))
                .isInstanceOf(DocumentFileTooLargeException.class)
                .hasMessage("DOCX archive entry exceeds the private beta limit");

        setUp();
        properties.setMaximumDocxExpansionRatio(1);
        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.DOCX,
                        "generated.docx",
                        DocumentFileValidator.DOCX_MIME_TYPE,
                        docx(entries(
                                "word/styles.xml",
                                "A".repeat(20_000)))))
                .isInstanceOf(DocumentFileTooLargeException.class)
                .hasMessage("DOCX compression ratio exceeds the private beta limit");
    }

    @Test
    void corruptOrIncompleteDocxIsRejected() {
        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.DOCX,
                        "generated.docx",
                        DocumentFileValidator.DOCX_MIME_TYPE,
                        "PK\u0003\u0004broken".getBytes(StandardCharsets.ISO_8859_1)))
                .hasMessageMatching("DOCX (archive is corrupt|required document parts are missing)");

        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.DOCX,
                        "generated.docx",
                        DocumentFileValidator.DOCX_MIME_TYPE,
                        archive(Map.of("word/document.xml", bytes(DOCUMENT_XML)))))
                .hasMessage("DOCX required document parts are missing");
    }

    private void assertInvalidDocx(Map<String, byte[]> replacements, String message) {
        assertThatThrownBy(() -> validator.validateGenerated(
                        FileType.DOCX,
                        "generated.docx",
                        DocumentFileValidator.DOCX_MIME_TYPE,
                        docx(replacements)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private static byte[] validPdf() {
        return TestDocumentFiles.validPdf();
    }

    private static PDActionURI uriAction(String target) {
        PDActionURI action = new PDActionURI();
        action.setURI(target);
        return action;
    }

    private static PDActionLaunch launchAction() {
        PDActionLaunch action = new PDActionLaunch();
        action.setF("unsafe-application");
        return action;
    }

    private static byte[] pdfWithLinkAction(PDAction action) {
        return pdfWithLinkAction(action, link -> {});
    }

    private static byte[] pdfWithLinkAction(
            PDAction action, Consumer<PDAnnotationLink> linkCustomizer) {
        return pdfWithMutation(document -> {
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 680, 240, 16));
            if (action != null) {
                link.setAction(action);
            }
            linkCustomizer.accept(link);
            document.getPage(0).setAnnotations(List.of(link));
        });
    }

    private static byte[] pdfWithTextAnnotation() {
        return pdfWithMutation(document -> {
            PDAnnotationText annotation = new PDAnnotationText();
            annotation.setRectangle(new PDRectangle(72, 680, 24, 24));
            annotation.setContents("Synthetic note");
            document.getPage(0).setAnnotations(List.of(annotation));
        });
    }

    private static byte[] encryptedPdf() {
        return pdfWithMutation(document -> {
            try {
                document.protect(new StandardProtectionPolicy(
                        "synthetic-owner-password",
                        "synthetic-user-password",
                        new AccessPermission()));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static byte[] pdfWithMutation(Consumer<PDDocument> mutation) {
        try (PDDocument document = Loader.loadPDF(TestDocumentFiles.validPdf());
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            mutation.accept(document);
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] validDocx() {
        return docx(Map.of());
    }

    private static Map<String, byte[]> entries(String name, String content) {
        return Map.of(name, bytes(content));
    }

    private static byte[] docx(Map<String, byte[]> replacements) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", bytes(CONTENT_TYPES));
        entries.put("word/document.xml", bytes(DOCUMENT_XML));
        replacements.forEach(entries::put);
        return archive(entries);
    }

    private static byte[] archive(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip =
                        new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private record ExternalRelationship(String type, String target) {}

    private static final class MediaTypes {
        private static final String OCTET_STREAM = "application/octet-stream";
    }
}
