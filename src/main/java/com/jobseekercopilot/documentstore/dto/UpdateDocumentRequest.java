package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateDocumentRequest {

    @NotBlank(message = "title is required")
    private String title;

    @NotBlank(message = "content is required and cannot be empty")
    private String content;

    @NotNull(message = "documentType is required")
    private DocumentType documentType;
}