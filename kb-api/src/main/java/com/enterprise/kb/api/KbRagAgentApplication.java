package com.enterprise.kb.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.enterprise.kb")
@EnableJpaRepositories(basePackages = "com.enterprise.kb.domain.repository")
@EnableAsync
public class KbRagAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbRagAgentApplication.class, args);
    }
}
