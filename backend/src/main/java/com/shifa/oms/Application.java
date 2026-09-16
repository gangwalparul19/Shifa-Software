package com.shifa.oms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

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
        // The business runs entirely in India (AWS Mumbai region), so every
        // timestamp the app generates or displays must be IST. Most of the
        // codebase stamps timestamps via bare `LocalDateTime.now()` or
        // `Clock.systemDefaultZone()`, both of which resolve against the JVM's
        // default zone — which in turn defaults to the OS timezone (UTC on a
        // bare Ubuntu/EC2 AMI unless someone has run `timedatectl set-timezone`).
        // Pinning it here, before the Spring context starts, makes the fix
        // self-contained in the app (works identically on any server/OS/region
        // this ever gets deployed/migrated to, without relying on a manual
        // server-level timezone step being remembered).
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(Application.class, args);
    }
}
