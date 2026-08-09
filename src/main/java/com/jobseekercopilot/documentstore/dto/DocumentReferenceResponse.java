package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentGroundingState;
import com.jobseekercopilot.documentstore.entity.DocumentType;
import com.jobseekercopilot.documentstore.entity.DocumentSourceType;
import com.jobseekercopilot.documentstore.entity.DocumentExtractionState;
import com.jobseekercopilot.documentstore.entity.FileType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Owner-scoped immutable document-version reference without document content")
public class DocumentReferenceResponse {

    private UUID documentId;
    private UUID documentFamilyId;
    private String jobId;
    private String applicationId;
    private DocumentType documentType;
    private Integer version;
    private String contentSha256;
    private String originalContentSha256;
    private Long originalContentSize;
    private UUID originalArtifactId;
    private FileType originalFileType;
    private DocumentExtractionState extractionState;
    private DocumentSourceType sourceType;
    private DocumentLifecycleState lifecycleState;
    private boolean current;
    private GenerationMetadata generationMetadata;
    private DocumentEvidenceProvenance evidenceProvenance;
    private DocumentGroundingState groundingState;
    private UUID parentDocumentId;
    private Integer parentDocumentVersion;
}
