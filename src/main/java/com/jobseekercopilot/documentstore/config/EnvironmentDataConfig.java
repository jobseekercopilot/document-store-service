package com.jobseekercopilot.documentstore.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EnvironmentDataProperties.class)
public class EnvironmentDataConfig {
}
