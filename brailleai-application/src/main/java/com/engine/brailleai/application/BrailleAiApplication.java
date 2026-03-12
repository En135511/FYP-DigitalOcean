package com.engine.brailleai.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main Spring Boot entrypoint that wires all modules under com.engine.brailleai.
 */
@SpringBootApplication(scanBasePackages = "com.engine.brailleai")
public class BrailleAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrailleAiApplication.class, args);
    }
}
