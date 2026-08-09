package com.jobseekercopilot.documentstore.upload;

import com.jobseekercopilot.documentstore.config.DocumentUploadProperties;
import com.jobseekercopilot.documentstore.entity.DocumentExtractionState;
import com.jobseekercopilot.documentstore.entity.FileType;
import com.jobseekercopilot.documentstore.storage.ObjectIntegrity;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextExtractor {

    private static final String DOCUMENT_XML = "word/document.xml";

    private final DocumentUploadProperties properties;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "document-upload-extractor");
        thread.setDaemon(true);
        return thread;
    });

    public DocumentTextExtractor(DocumentUploadProperties properties) {
        this.properties = properties;
    }

    public ExtractedDocumentText extract(FileType fileType, byte[] content) {
        var future = executor.submit(() -> switch (fileType) {
            case PDF -> extractPdf(content);
            case DOCX -> extractDocx(content);
        });
        try {
            String normalized = normalize(future.get(
                    properties.getExtractionTimeoutSeconds(), TimeUnit.SECONDS));
            DocumentExtractionState state = normalized.isEmpty()
                    ? DocumentExtractionState.NO_TEXT
                    : DocumentExtractionState.SUCCEEDED;
            return new ExtractedDocumentText(
                    normalized,
                    state,
                    ObjectIntegrity.sha256(
                            normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IllegalArgumentException("Document text extraction timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Document text extraction was interrupted", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException("Document text extraction failed");
        }
    }

    private String extractPdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("Encrypted PDF files are not supported");
            }
            if (document.getNumberOfPages() > properties.getMaximumPdfPages()) {
                throw new IllegalArgumentException("PDF contains too many pages");
            }
            return new PDFTextStripper().getText(document);
        } catch (IOException exception) {
            throw new IllegalArgumentException("PDF structure is invalid");
        }
    }

    private String extractDocx(byte[] content) {
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (DOCUMENT_XML.equalsIgnoreCase(entry.getName())) {
                    return extractWordXml(zip.readAllBytes());
                }
            }
            throw new IllegalArgumentException("DOCX document content is missing");
        } catch (IOException exception) {
            throw new IllegalArgumentException("DOCX structure is invalid");
        }
    }

    private String extractWordXml(byte[] xml) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        StringBuilder text = new StringBuilder();
        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    if ("t".equals(localName)) {
                        appendBounded(text, reader.getElementText());
                    } else if ("tab".equals(localName)) {
                        appendBounded(text, "\t");
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && "p".equals(reader.getLocalName())) {
                    appendBounded(text, "\n");
                }
            }
            reader.close();
            return text.toString();
        } catch (XMLStreamException exception) {
            throw new IllegalArgumentException("DOCX XML content is invalid");
        }
    }

    private void appendBounded(StringBuilder target, String value) {
        if (target.length() + value.length()
                > properties.getMaximumExtractedCharacters()) {
            throw new IllegalArgumentException(
                    "Extracted document text exceeds the private beta limit");
        }
        target.append(value);
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(
                        value == null ? "" : value, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        StringBuilder result = new StringBuilder(normalized.length());
        boolean whitespace = false;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == '\n') {
                while (result.length() > 0
                        && result.charAt(result.length() - 1) == ' ') {
                    result.deleteCharAt(result.length() - 1);
                }
                if (result.length() == 0
                        || result.charAt(result.length() - 1) != '\n') {
                    result.append('\n');
                }
                whitespace = false;
            } else if (Character.isWhitespace(character)) {
                whitespace = true;
            } else {
                if (whitespace && result.length() > 0
                        && result.charAt(result.length() - 1) != '\n') {
                    result.append(' ');
                }
                result.append(character);
                whitespace = false;
            }
            if (result.length() > properties.getMaximumExtractedCharacters()) {
                throw new IllegalArgumentException(
                        "Extracted document text exceeds the private beta limit");
            }
        }
        return result.toString().strip();
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
