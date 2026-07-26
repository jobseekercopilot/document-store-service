package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentLifecycleState;
import com.jobseekercopilot.documentstore.entity.DocumentType;
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
    private DocumentLifecycleState lifecycleState;
    private boolean current;
    private GenerationMetadata generationMetadata;
}
