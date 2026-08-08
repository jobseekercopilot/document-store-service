package com.jobseekercopilot.documentstore.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ValidatedClaimLedgerCapacityTest {

    private final Validator validator = Validation
            .buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void acceptsTwoHundredClaimsAndRejectsTwoHundredAndOne() {
        assertTrue(validator.validate(ledger(200)).isEmpty());

        var violations = validator.validate(ledger(201));

        assertEquals(1, violations.size());
        assertEquals(
                "claims",
                violations.iterator().next().getPropertyPath().toString());
    }

    private ValidatedClaimLedger ledger(int claimCount) {
        List<ValidatedClaimLedger.ValidatedClaim> claims = IntStream
                .range(0, claimCount)
                .mapToObj(index -> new ValidatedClaimLedger.ValidatedClaim(
                        "CLAIM-" + String.format("%03d", index),
                        ValidatedClaimLedger.ClaimDisposition.SUPPORTED,
                        List.of(),
                        List.of(),
                        ""))
                .toList();
        return new ValidatedClaimLedger(
                UUID.randomUUID(),
                "a".repeat(64),
                "2.19.0",
                "3.5.2",
                claims);
    }
}
