package com.jobseekercopilot.documentstore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "Atomic, replay-safe cleanup command owned by an Application Tracker withdrawal workflow")
public class ApplicationWithdrawalCleanupRequest {

    @NotNull
    @Schema(description = "Durable Application Tracker workflow operation ID")
    private UUID operationId;

    @NotNull
    @Schema(description = "Application whose generated-only draft is being withdrawn")
    private UUID applicationId;

    @NotEmpty
    @Size(max = 2)
    @Schema(description = "Exact owner-scoped generated document version IDs")
    private List<@NotNull UUID> documentIds;
}
