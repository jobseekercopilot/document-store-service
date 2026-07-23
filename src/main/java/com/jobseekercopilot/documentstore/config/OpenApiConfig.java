package com.jobseekercopilot.documentstore.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI documentStoreOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Jobseeker Copilot - Document Store API")
                        .description("""
                                Service for storing and retrieving generated CVs and cover letters.
                                
                                This service stores generated document content.
                                It does NOT generate documents.
                                It does NOT call an LLM.
                                It does NOT export PDF/DOCX.
                                
                                DocumentType values:
                                - CV - Curriculum Vitae / Resume
                                - COVER_LETTER - Cover letter for a job application
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Jobseeker Copilot"))
                        .license(new License()
                                .name("MIT")));
    }
}