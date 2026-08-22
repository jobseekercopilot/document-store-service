package com.jobseekercopilot.documentstore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DocumentStoreServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentStoreServiceApplication.class, args);
    }
}
