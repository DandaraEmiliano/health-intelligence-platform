package dev.healthintelligence.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Entry point of the core modular monolith (ADR 0001).
 *
 * <p>Each direct sub-package is an application module. Boundaries are verified by
 * {@code ModularityTests}.
 */
@Modulithic(systemName = "Health Intelligence Platform - core-api", sharedModules = "shared")
@SpringBootApplication
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
