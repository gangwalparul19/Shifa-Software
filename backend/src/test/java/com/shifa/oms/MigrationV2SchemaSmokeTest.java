package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V2 Flyway migration (settings + product HSN).
 *
 * <p>Like {@link MigrationSchemaSmokeTest}, this verifies at the
 * migration-definition level (no Docker/Testcontainers) that the V2 script
 * declares the {@code app_settings} table with its GST columns, seeds the single
 * settings row, and adds the {@code hsn_code} column to {@code products}.
 */
class MigrationV2SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV2SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V2__settings_and_hsn.sql")) {
            assertNotNull(in, "V2 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationCreatesAppSettingsTable() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table app_settings "), "V2 must create app_settings");
        assertTrue(sql.contains("engine=innodb"), "app_settings must use InnoDB");
        assertTrue(sql.contains("charset=utf8mb4"), "app_settings must use utf8mb4");
    }

    @Test
    void migrationDeclaresAllGstColumns() throws IOException {
        String sql = migration();
        for (String column : new String[] {
                "gst_enabled", "gstin", "legal_name", "address_line", "city", "state",
                "state_code", "gst_rate_percent", "prices_include_gst", "invoice_footer_note",
                "contact_phone", "contact_email"}) {
            assertTrue(sql.contains(column), "app_settings must declare column: " + column);
        }
        assertTrue(sql.contains("decimal(5,2)"), "gst_rate_percent must be DECIMAL(5,2)");
    }

    @Test
    void migrationSeedsSingleSettingsRowWithGstDisabled() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("insert into app_settings"), "V2 must seed a settings row");
        assertTrue(sql.contains("false"), "seeded settings row must have GST disabled");
    }

    @Test
    void migrationAddsHsnCodeColumnToProducts() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("alter table products add column hsn_code"),
                "V2 must add products.hsn_code");
    }
}
