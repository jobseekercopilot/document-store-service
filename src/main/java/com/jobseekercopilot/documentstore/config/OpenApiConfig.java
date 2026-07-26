package com.jobseekercopilot.documentstore.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI documentStoreOpenAPI() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT"))
                        .addSecuritySchemes(
                                "serviceToken",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Service-Token"))
                        .addSecuritySchemes(
                                "environmentDataToken",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-Environment-Data-Token")))
                .info(new Info()
                        .title("Jobseeker Copilot - Document Store API")
                        .description("""
                                Service for storing and retrieving generated CVs and cover letters.

                                This service stores generated document content.
                                It does NOT generate documents.
                                It does NOT call an LLM.
                                It does NOT export PDF/DOCX.

                                Every document and file operation is authenticated and owner scoped.
                                User ownership comes from a validated access-token subject. Approved
                                service identities must bind the same owner in X-Document-Owner.

                                DocumentType values:
                                - CV - Curriculum Vitae / Resume
                                - COVER_LETTER - Cover letter for a job application
                                """)
                        .version("1.3.0")
                        .contact(new Contact()
                                .name("Jobseeker Copilot"))
                        .license(new License()
                                .name("Proprietary and confidential")))
                .servers(List.of(new Server()
                        .url("http://localhost:8089")
                        .description("Local development")));
    }
}
