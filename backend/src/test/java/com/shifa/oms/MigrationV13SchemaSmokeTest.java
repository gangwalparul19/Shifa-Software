package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V13 Flyway migration (company logo + default low-stock
 * threshold on app_settings). Verifies at the migration-definition level that
 * V13 additively adds {@code app_settings.logo_object_key} and
 * {@code app_settings.low_stock_threshold}.
 */
class MigrationV13SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV13SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V13__settings_logo_and_threshold.sql")) {
            assertNotNull(in, "V13 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationAddsLogoObjectKeyColumn() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("alter table app_settings add column logo_object_key"),
                "V13 must add app_settings.logo_object_key");
    }

    @Test
    void migrationAddsDefaultLowStockThresholdColumn() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("alter table app_settings add column low_stock_threshold"),
                "V13 must add app_settings.low_stock_threshold");
        assertTrue(sql.contains("default 5"), "default low_stock_threshold must be 5");
    }

    @Test
    void migrationIsAdditiveOnly() throws IOException {
        String sql = migration();
        assertTrue(!sql.contains("drop table") && !sql.contains("drop column"),
                "V13 must be additive only (no drops)");
    }
}
