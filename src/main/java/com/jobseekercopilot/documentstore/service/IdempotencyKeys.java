package com.jobseekercopilot.documentstore.service;

import java.util.regex.Pattern;

final class IdempotencyKeys {
    static final String HEADER = "Idempotency-Key";
    private static final Pattern SAFE_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private IdempotencyKeys() {
    }

    static String validate(String value) {
        if (value == null) {
            return null;
        }
        if (!SAFE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be 1-128 URL-safe characters");
        }
        return value;
    }
}
