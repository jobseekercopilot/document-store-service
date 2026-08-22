package com.jobseekercopilot.documentstore.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobseekercopilot.documentstore.TestDocumentFiles;
import com.jobseekercopilot.documentstore.config.DocumentUploadProperties;
import com.jobseekercopilot.documentstore.entity.DocumentExtractionState;
import com.jobseekercopilot.documentstore.entity.FileType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DocumentTextExtractorTest {

    private final DocumentUploadProperties properties =
            new DocumentUploadProperties();
    private final DocumentTextExtractor extractor =
            new DocumentTextExtractor(properties);

    @AfterEach
    void close() {
        extractor.close();
    }

    @Test
    void extractsAndNormalisesDocxDeterministically() {
        var first = extractor.extract(FileType.DOCX, TestDocumentFiles.validDocx());
        var replay = extractor.extract(FileType.DOCX, TestDocumentFiles.validDocx());

        assertThat(first.text()).isEqualTo("Synthetic CV");
        assertThat(first.state()).isEqualTo(DocumentExtractionState.SUCCEEDED);
        assertThat(first.sha256()).isEqualTo(replay.sha256());
    }

    @Test
    void extractsPdfAndRepresentsImageOnlyPdfWithoutInventingText() {
        var textPdf = extractor.extract(FileType.PDF, TestDocumentFiles.validPdf());
        var imageOnly = extractor.extract(
                FileType.PDF, TestDocumentFiles.imageOnlyPdf());

        assertThat(textPdf.text()).isEqualTo("Synthetic CV");
        assertThat(textPdf.state()).isEqualTo(DocumentExtractionState.SUCCEEDED);
        assertThat(imageOnly.text()).isEmpty();
        assertThat(imageOnly.state()).isEqualTo(DocumentExtractionState.NO_TEXT);
        assertThat(imageOnly.sha256()).hasSize(64);
    }
}
