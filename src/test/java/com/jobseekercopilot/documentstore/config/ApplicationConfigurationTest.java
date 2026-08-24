package com.jobseekercopilot.documentstore.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;

class ApplicationConfigurationTest {

    @Test
    void reliesOnHikariFailFastDefaultWithoutLateRuntimeBinding() throws IOException {
        assertThat(new HikariConfig().getInitializationFailTimeout()).isEqualTo(1L);

        List<String> configuredHikariProperties =
                new YamlPropertySourceLoader()
                        .load(
                                "application",
                                new ClassPathResource("application.yml"))
                        .stream()
                        .filter(EnumerablePropertySource.class::isInstance)
                        .map(EnumerablePropertySource.class::cast)
                        .map(EnumerablePropertySource::getPropertyNames)
                        .flatMap(Arrays::stream)
                        .filter(name -> name.startsWith("spring.datasource.hikari."))
                        .toList();

        assertThat(configuredHikariProperties).isEmpty();
    }
}
