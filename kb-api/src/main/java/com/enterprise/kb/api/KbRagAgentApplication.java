package com.enterprise.kb.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class KbRagAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbRagAgentApplication.class, args);
    }
}
