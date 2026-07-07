package com.shifa.oms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Shifa Herbal Remedies Order Management System (OMS).
 *
 * <p>The application is a modular monolith: each business capability lives in a
 * cohesive package (see the {@code auth}, {@code product}, {@code order},
 * {@code statemachine}, {@code label}, {@code courier}, {@code notification},
 * {@code reconciliation}, {@code reporting}, {@code agent}, {@code dashboard},
 * {@code platform}, and {@code common} sub-packages) but deploys as a single
 * Spring Boot artifact, in line with the OCI Always Free tier budget.
 */
@SpringBootApplication
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
