package com.jobseekercopilot.documentstore.service;

import com.jobseekercopilot.documentstore.dto.DocumentApplicationAssociationsSnapshot;
import com.jobseekercopilot.documentstore.dto.UpdateDocumentAvailabilityProjectionRequest;
import com.jobseekercopilot.documentstore.entity.DocumentRetentionState;
import com.jobseekercopilot.documentstore.exception.OperationConflictException;
import java.util.UUID;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class RestApplicationAssociationClient
        implements ApplicationAssociationClient {
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String OWNER_HEADER = "X-Application-Owner";

    private final RestTemplate restTemplate;
    private final String trackerBaseUrl;
    private final String trackerReaderToken;
    private final String trackerProducerToken;

    public RestApplicationAssociationClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${services.application-tracker-service.base-url}")
            String trackerBaseUrl,
            @Value("${services.application-tracker-service.reader-token:}")
            String trackerReaderToken,
            @Value("${services.application-tracker-service.producer-token:}")
            String trackerProducerToken) {
        this.restTemplate = restTemplateBuilder.build();
        this.trackerBaseUrl = trackerBaseUrl;
        this.trackerReaderToken = trackerReaderToken;
        this.trackerProducerToken = trackerProducerToken;
    }

    @Override
    public void updateAvailability(
            String ownerId,
            UUID documentId,
            DocumentRetentionState availability,
            String unavailableReason,
            LocalDateTime occurredAt) {
        if (trackerProducerToken == null || trackerProducerToken.isBlank()) {
            throw lifecycleUnavailable();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, trackerProducerToken);
        headers.set(OWNER_HEADER, ownerId);
        try {
            restTemplate.exchange(
                    trackerBaseUrl
                            + "/api/v1/applications/document/{documentId}/availability",
                    HttpMethod.PUT,
                    new HttpEntity<>(
                            new UpdateDocumentAvailabilityProjectionRequest(
                                    availability,
                                    unavailableReason,
                                    occurredAt),
                            headers),
                    Void.class,
                    documentId);
        } catch (RestClientException exception) {
            throw lifecycleUnavailable();
        }
    }

    @Override
    public DocumentApplicationAssociationsSnapshot associations(
            String ownerId,
            UUID documentId) {
        if (trackerReaderToken == null || trackerReaderToken.isBlank()) {
            throw unavailable();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, trackerReaderToken);
        headers.set(OWNER_HEADER, ownerId);
        try {
            DocumentApplicationAssociationsSnapshot response = restTemplate
                    .exchange(
                            trackerBaseUrl
                                    + "/api/v1/applications/document/{documentId}/associations",
                            HttpMethod.GET,
                            new HttpEntity<>(headers),
                            DocumentApplicationAssociationsSnapshot.class,
                            documentId)
                    .getBody();
            if (response == null
                    || !documentId.equals(response.documentId())
                    || response.associations() == null
                    || response.associationCount()
                            != response.associations().size()) {
                throw unavailable();
            }
            return response;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private OperationConflictException unavailable() {
        return new OperationConflictException(
                "Application associations could not be verified; irreversible purge remains blocked.");
    }

    private OperationConflictException lifecycleUnavailable() {
        return new OperationConflictException(
                "Application availability could not be projected; document lifecycle remains unchanged.");
    }
}
