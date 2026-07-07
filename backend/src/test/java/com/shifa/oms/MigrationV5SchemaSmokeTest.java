package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V5 Flyway migration (product reviews & ratings).
 *
 * <p>Like the V1-V4 smoke tests, this verifies at the migration-definition level
 * (no Docker/Testcontainers) that the V5 script creates the {@code product_reviews}
 * table with its FK/CHECK constraints and indexes, using the shared
 * InnoDB/utf8mb4 conventions.
 */
class MigrationV5SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV5SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V5__product_reviews.sql")) {
            assertNotNull(in, "V5 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationCreatesProductReviewsTable() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table product_reviews"), "V5 must create product_reviews");
        assertTrue(sql.contains("engine=innodb"), "product_reviews must use InnoDB");
        assertTrue(sql.contains("charset=utf8mb4"), "product_reviews must use utf8mb4");
    }

    @Test
    void migrationDeclaresForeignKeysAndConstraints() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("fk_product_reviews_product"), "must declare the product FK");
        assertTrue(sql.contains("fk_product_reviews_user"), "must declare the user FK");
        assertTrue(sql.contains("ck_product_reviews_rating"), "must enforce the 1..5 rating CHECK");
        assertTrue(sql.contains("rating between 1 and 5"), "rating CHECK must bound 1..5");
        assertTrue(sql.contains("default 'pending'"), "status must default to PENDING");
    }

    @Test
    void migrationCreatesModerationIndexes() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("ix_product_reviews_product_status"),
                "must index (product_id, status) for the public read");
        assertTrue(sql.contains("ix_product_reviews_status"),
                "must index status for the moderation queue");
    }
}
