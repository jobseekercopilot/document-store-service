package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.config.DocumentFileValidationProperties;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.exception.DocumentFileTooLargeException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

@Component
public class DocumentFileValidator {

    public static final String DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String PDF_MIME_TYPE = "application/pdf";

    private static final String DOCX_MAIN_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final String CONTENT_TYPES_ENTRY = "[content_types].xml";
    private static final String DOCUMENT_ENTRY = "word/document.xml";
    private static final int MAXIMUM_FILE_NAME_CHARACTERS = 255;
    private static final int MAXIMUM_ENTRY_NAME_CHARACTERS = 512;
    private static final int PDF_TRAILER_SEARCH_BYTES = 1024;
    private static final Set<String> FORBIDDEN_PDF_TOKENS = Set.of(
            "/Encrypt",
            "/JavaScript",
            "/JS",
            "/Launch",
            "/EmbeddedFile",
            "/OpenAction",
            "/AA");

    private final DocumentFileValidationProperties properties;

    public DocumentFileValidator(DocumentFileValidationProperties properties) {
        this.properties = properties;
    }

    public byte[] decodeAndValidateGenerated(
            FileType fileType,
            String fileName,
            String declaredMimeType,
            String encodedContent) {
        validateEncodedLength(encodedContent);
        byte[] content;
        try {
            content = Base64.getDecoder().decode(encodedContent);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("File content must be valid Base64");
        }
        validateGenerated(fileType, fileName, declaredMimeType, content);
        return content;
    }

    public void validateGenerated(
            FileType fileType,
            String fileName,
            String declaredMimeType,
            byte[] content) {
        validate(fileType, fileName, declaredMimeType, content, false);
    }

    public void validateUserUpload(
            FileType fileType,
            String fileName,
            String declaredMimeType,
            byte[] content) {
        if (fileType != FileType.DOCX) {
            throw new IllegalArgumentException(
                    "Private beta replacement uploads support DOCX only");
        }
        validate(fileType, fileName, declaredMimeType, content, true);
    }

    public void validateApplicationUpload(
            FileType fileType,
            String fileName,
            String declaredMimeType,
            byte[] content) {
        validate(fileType, fileName, declaredMimeType, content, true);
    }

    public void validateStored(FileType fileType, byte[] content) {
        validateContent(fileType, content);
    }

    public void validateDeclaredSize(long fileSize) {
        if (fileSize > properties.getMaximumFileBytes()) {
            throw tooLarge();
        }
    }

    public String canonicalMimeType(FileType fileType) {
        requireSupportedType(fileType);
        return switch (fileType) {
            case DOCX -> DOCX_MIME_TYPE;
            case PDF -> PDF_MIME_TYPE;
        };
    }

    public String safeFileName(UUID fileId, FileType fileType) {
        if (fileId == null) {
            throw new IllegalArgumentException("File ID is required");
        }
        requireSupportedType(fileType);
        return "document-" + fileId + "." + fileType.name().toLowerCase(Locale.ROOT);
    }

    private void validate(
            FileType fileType,
            String fileName,
            String declaredMimeType,
            byte[] content,
            boolean userUpload) {
        requireSupportedType(fileType);
        validateFileName(fileName, fileType);
        validateDeclaredMimeType(declaredMimeType, fileType, userUpload);
        validateContent(fileType, content);
    }

    private void validateEncodedLength(String encodedContent) {
        if (encodedContent == null || encodedContent.isBlank()) {
            throw new IllegalArgumentException("File content is required");
        }
        long maximumEncodedCharacters =
                4L * ((properties.getMaximumFileBytes() + 2L) / 3L);
        if (encodedContent.length() > maximumEncodedCharacters) {
            throw tooLarge();
        }
    }

    private void validateContent(FileType fileType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("File content is required");
        }
        if (content.length > properties.getMaximumFileBytes()) {
            throw tooLarge();
        }
        switch (fileType) {
            case PDF -> validatePdf(content);
            case DOCX -> validateDocx(content);
        }
    }

    private void validateFileName(String fileName, FileType fileType) {
        if (fileName == null
                || fileName.isBlank()
                || fileName.length() > MAXIMUM_FILE_NAME_CHARACTERS
                || !fileName.equals(fileName.strip())
                || !fileName.equals(Normalizer.normalize(fileName, Normalizer.Form.NFKC))) {
            throw new IllegalArgumentException("Uploaded filename is invalid");
        }
        if (fileName.codePoints().anyMatch(this::isUnsafeFileNameCharacter)) {
            throw new IllegalArgumentException("Uploaded filename is invalid");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String expectedExtension = "." + fileType.name().toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(expectedExtension)
                || lowerName.length() == expectedExtension.length()) {
            throw new IllegalArgumentException(
                    "Uploaded file extension does not match " + fileType);
        }
    }

    private boolean isUnsafeFileNameCharacter(int character) {
        int type = Character.getType(character);
        return Character.isISOControl(character)
                || type == Character.FORMAT
                || character == '/'
                || character == '\\'
                || character == ':';
    }

    private void validateDeclaredMimeType(
            String declaredMimeType,
            FileType fileType,
            boolean userUpload) {
        if (declaredMimeType == null || declaredMimeType.isBlank()) {
            if (userUpload) {
                return;
            }
            throw new IllegalArgumentException("File MIME type is required");
        }
        String normalized;
        try {
            MediaType parsed = MediaType.parseMediaType(declaredMimeType);
            normalized = parsed.getType().toLowerCase(Locale.ROOT)
                    + "/"
                    + parsed.getSubtype().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("File MIME type is invalid");
        }
        if (MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(normalized)) {
            if (userUpload) {
                return;
            }
            throw new IllegalArgumentException(
                    "Uploaded file MIME type does not match " + fileType);
        }
        if (!canonicalMimeType(fileType).equals(normalized)) {
            throw new IllegalArgumentException(
                    "Uploaded file MIME type does not match " + fileType);
        }
    }

    private void validatePdf(byte[] content) {
        if (content.length < 12
                || content[0] != '%'
                || content[1] != 'P'
                || content[2] != 'D'
                || content[3] != 'F'
                || content[4] != '-'
                || !isSupportedPdfVersion(content)) {
            throw new IllegalArgumentException("File content does not match PDF");
        }
        String searchable = new String(content, StandardCharsets.ISO_8859_1);
        int trailer = searchable.lastIndexOf("%%EOF");
        if (trailer < Math.max(0, content.length - PDF_TRAILER_SEARCH_BYTES)
                || hasNonWhitespaceAfterTrailer(searchable, trailer + 5)) {
            throw new IllegalArgumentException("PDF file is corrupt or incomplete");
        }
        for (String forbiddenToken : FORBIDDEN_PDF_TOKENS) {
            if (searchable.contains(forbiddenToken)) {
                throw new IllegalArgumentException(
                        "PDF active or encrypted content is not supported");
            }
        }
    }

    private boolean isSupportedPdfVersion(byte[] content) {
        return content.length > 7
                && ((content[5] == '1' && content[6] == '.' && content[7] >= '0'
                                && content[7] <= '7')
                        || (content[5] == '2' && content[6] == '.' && content[7] == '0'));
    }

    private boolean hasNonWhitespaceAfterTrailer(String content, int offset) {
        for (int index = offset; index < content.length(); index++) {
            if (!Character.isWhitespace(content.charAt(index))
                    && content.charAt(index) != '\0') {
                return true;
            }
        }
        return false;
    }

    private void validateDocx(byte[] content) {
        if (content.length < 4
                || content[0] != 'P'
                || content[1] != 'K'
                || content[2] != 3
                || content[3] != 4) {
            throw new IllegalArgumentException("File content does not match DOCX");
        }

        Map<String, byte[]> inspectedEntries = new HashMap<>();
        Set<String> entryNames = new HashSet<>();
        long expandedBytes = 0;
        int entryCount = 0;
        try (ZipInputStream zip =
                new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > properties.getMaximumDocxEntries()) {
                    throw new DocumentFileTooLargeException(
                            "DOCX contains too many archive entries");
                }
                String normalizedName = validateEntryName(entry.getName());
                if (!entryNames.add(normalizedName)) {
                    throw new IllegalArgumentException(
                            "DOCX contains duplicate archive entries");
                }
                rejectActiveDocxEntry(normalizedName);
                EntryRead entryRead = readEntry(zip, shouldInspect(normalizedName));
                expandedBytes += entryRead.expandedBytes();
                if (expandedBytes > properties.getMaximumDocxExpandedBytes()) {
                    throw new DocumentFileTooLargeException(
                            "DOCX expanded content exceeds the private beta limit");
                }
                if (entryRead.content() != null) {
                    inspectedEntries.put(normalizedName, entryRead.content());
                }
                zip.closeEntry();
            }
        } catch (ZipException exception) {
            throw new IllegalArgumentException("DOCX archive is corrupt");
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to inspect DOCX archive");
        }

        if (entryCount == 0) {
            throw new IllegalArgumentException("DOCX archive is corrupt");
        }
        if (expandedBytes
                > (long) content.length * properties.getMaximumDocxExpansionRatio()) {
            throw new DocumentFileTooLargeException(
                    "DOCX compression ratio exceeds the private beta limit");
        }
        byte[] contentTypes = inspectedEntries.get(CONTENT_TYPES_ENTRY);
        byte[] documentXml = inspectedEntries.get(DOCUMENT_ENTRY);
        if (contentTypes == null || documentXml == null) {
            throw new IllegalArgumentException("DOCX required document parts are missing");
        }
        validateContentTypes(contentTypes);
        validateDocumentXml(documentXml);
        inspectedEntries.forEach((name, bytes) -> {
            if (name.endsWith(".rels")) {
                validateRelationships(bytes);
            }
        });
    }

    private String validateEntryName(String entryName) {
        if (entryName == null
                || entryName.isBlank()
                || entryName.length() > MAXIMUM_ENTRY_NAME_CHARACTERS
                || entryName.startsWith("/")
                || entryName.startsWith("\\")
                || entryName.indexOf('\\') >= 0
                || entryName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("DOCX contains an unsafe archive path");
        }
        String[] segments = entryName.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            boolean trailingDirectory = index == segments.length - 1
                    && segment.isEmpty()
                    && entryName.endsWith("/");
            if ((!trailingDirectory && segment.isEmpty())
                    || ".".equals(segment)
                    || "..".equals(segment)
                    || segment.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("DOCX contains an unsafe archive path");
            }
        }
        return entryName.toLowerCase(Locale.ROOT);
    }

    private void rejectActiveDocxEntry(String entryName) {
        if (entryName.endsWith("vbaproject.bin")
                || entryName.startsWith("word/activex/")
                || entryName.startsWith("word/embeddings/")
                || entryName.startsWith("customui/")
                || "encryptioninfo".equals(entryName)
                || "encryptedpackage".equals(entryName)) {
            throw new IllegalArgumentException(
                    "DOCX active, embedded or encrypted content is not supported");
        }
    }

    private EntryRead readEntry(ZipInputStream zip, boolean inspect) throws IOException {
        ByteArrayOutputStream captured = inspect ? new ByteArrayOutputStream() : null;
        byte[] buffer = new byte[8192];
        long expandedBytes = 0;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            expandedBytes += read;
            if (expandedBytes > properties.getMaximumDocxEntryBytes()) {
                throw new DocumentFileTooLargeException(
                        "DOCX archive entry exceeds the private beta limit");
            }
            if (captured != null) {
                captured.write(buffer, 0, read);
            }
        }
        return new EntryRead(
                expandedBytes, captured == null ? null : captured.toByteArray());
    }

    private boolean shouldInspect(String entryName) {
        return CONTENT_TYPES_ENTRY.equals(entryName)
                || DOCUMENT_ENTRY.equals(entryName)
                || entryName.endsWith(".rels");
    }

    private void validateContentTypes(byte[] content) {
        Document document = parseXml(content);
        NodeList overrides = document.getElementsByTagNameNS("*", "Override");
        boolean hasMainDocument = false;
        for (int index = 0; index < overrides.getLength(); index++) {
            Element element = (Element) overrides.item(index);
            String partName = element.getAttribute("PartName");
            String contentType = element.getAttribute("ContentType");
            if (contentType.toLowerCase(Locale.ROOT).contains("macroenabled")
                    || contentType.toLowerCase(Locale.ROOT).contains("activex")
                    || contentType.toLowerCase(Locale.ROOT).contains("oleobject")) {
                throw new IllegalArgumentException(
                        "DOCX active content is not supported");
            }
            if ("/word/document.xml".equalsIgnoreCase(partName)
                    && DOCX_MAIN_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
                hasMainDocument = true;
            }
        }
        if (!hasMainDocument) {
            throw new IllegalArgumentException("DOCX main document type is invalid");
        }
    }

    private void validateDocumentXml(byte[] content) {
        Document document = parseXml(content);
        if (document.getElementsByTagNameNS("*", "altChunk").getLength() > 0
                || document.getElementsByTagNameNS("*", "object").getLength() > 0
                || document.getElementsByTagNameNS("*", "control").getLength() > 0) {
            throw new IllegalArgumentException(
                    "DOCX imported or embedded content is not supported");
        }
    }

    private void validateRelationships(byte[] content) {
        Document document = parseXml(content);
        NodeList relationships =
                document.getElementsByTagNameNS("*", "Relationship");
        for (int index = 0; index < relationships.getLength(); index++) {
            Element relationship = (Element) relationships.item(index);
            if ("external".equalsIgnoreCase(
                    relationship.getAttribute("TargetMode"))) {
                throw new IllegalArgumentException(
                        "DOCX external relationships are not supported");
            }
        }
    }

    private Document parseXml(byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(content));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new IllegalArgumentException("DOCX XML content is invalid");
        }
    }

    private void requireSupportedType(FileType fileType) {
        if (fileType == null
                || (fileType != FileType.DOCX && fileType != FileType.PDF)) {
            throw new IllegalArgumentException("Only DOCX and PDF files are supported");
        }
    }

    private DocumentFileTooLargeException tooLarge() {
        return new DocumentFileTooLargeException(
                "File exceeds the private beta size limit");
    }

    private record EntryRead(long expandedBytes, byte[] content) {}
}
