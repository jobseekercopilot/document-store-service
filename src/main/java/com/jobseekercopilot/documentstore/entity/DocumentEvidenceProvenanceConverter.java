package com.jobseekercopilot.documentstore.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jobseekercopilot.documentstore.dto.DocumentEvidenceProvenance;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class DocumentEvidenceProvenanceConverter
        implements AttributeConverter<DocumentEvidenceProvenance, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(
                    SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
                    true)
            .disable(
                    SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public String convertToDatabaseColumn(
            DocumentEvidenceProvenance provenance) {
        return encode(provenance);
    }

    @Override
    public DocumentEvidenceProvenance convertToEntityAttribute(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(
                    json, DocumentEvidenceProvenance.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored document evidence provenance is invalid.",
                    exception);
        }
    }

    public static String encode(DocumentEvidenceProvenance provenance) {
        if (provenance == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(provenance);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Document evidence provenance is invalid.",
                    exception);
        }
    }
}
