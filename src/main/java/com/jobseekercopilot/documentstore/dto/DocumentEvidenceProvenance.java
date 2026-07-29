package com.jobseekercopilot.documentstore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(
        description = """
                Immutable evidence and claim provenance for one document version.
                Raw claimant evidence values are retained by the purpose-specific
                User Profile snapshot rather than duplicated here.
                """)
public record DocumentEvidenceProvenance(
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID profileRevisionId,
        @NotBlank
        @Pattern(regexp = "^[a-f0-9]{64}$")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String profileContentDigest,
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID evidenceSnapshotId,
        @NotBlank
        @Pattern(regexp = "^[a-f0-9]{64}$")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String evidenceSnapshotDigest,
        @Valid
        @NotEmpty
        @Size(max = 50)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<EvidenceRevisionReference> evidenceRevisions,
        @NotEmpty
        @Size(max = 9)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<EvidenceSection> sectionOrder,
        @Valid
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ValidatedClaimLedger claimLedger,
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        OffsetDateTime generatedAt) {

    public DocumentEvidenceProvenance {
        evidenceRevisions = evidenceRevisions == null
                ? null
                : List.copyOf(evidenceRevisions);
        sectionOrder =
                sectionOrder == null ? null : List.copyOf(sectionOrder);
    }
}
