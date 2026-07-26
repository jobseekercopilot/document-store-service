package com.jobseekercopilot.documentstore.storage;

import java.util.List;

public record ObjectKeyPage(List<String> keys, String nextAfterKey) {

    public ObjectKeyPage {
        keys = List.copyOf(keys);
    }
}
