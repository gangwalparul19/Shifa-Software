package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V12 Flyway migration (per-product GST rate). Verifies at the
 * migration-definition level that V12 additively adds the optional
 * {@code products.gst_rate} DECIMAL(5,2) column.
 */
class MigrationV12SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV12SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V12__product_gst_rate.sql")) {
            assertNotNull(in, "V12 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationAddsGstRateColumn() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("alter table products add column gst_rate"),
                "V12 must add products.gst_rate");
        assertTrue(sql.contains("decimal(5,2)"), "gst_rate must be DECIMAL(5,2)");
    }

    @Test
    void migrationIsAdditiveOnly() throws IOException {
        String sql = migration();
        assertTrue(!sql.contains("drop table") && !sql.contains("drop column"),
                "V12 must be additive only (no drops)");
    }
}
