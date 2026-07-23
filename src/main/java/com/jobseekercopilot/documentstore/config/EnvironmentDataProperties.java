package com.jobseekercopilot.documentstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "environment-data")
public class EnvironmentDataProperties {
    private boolean enabled = false;
    private List<String> allowedEnvironments = List.of("local", "test", "demo", "default");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedEnvironments() {
        return allowedEnvironments;
    }

    public void setAllowedEnvironments(List<String> allowedEnvironments) {
        this.allowedEnvironments = allowedEnvironments;
    }
}
