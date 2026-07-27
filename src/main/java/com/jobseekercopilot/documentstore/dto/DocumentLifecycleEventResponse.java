package com.jobseekercopilot.documentstore.dto;

import com.jobseekercopilot.documentstore.entity.DocumentLifecycleAction;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class DocumentLifecycleEventResponse {
    UUID id;
    UUID documentId;
    DocumentLifecycleAction action;
    DocumentRetentionState fromState;
    DocumentRetentionState toState;
    String actorId;
    String policyVersion;
    OffsetDateTime occurredAt;
}
