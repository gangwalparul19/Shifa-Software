package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V14 Flyway migration (Wave 2 admin-table indexes). Verifies
 * at the migration-definition level that V14 additively creates the receivables
 * {@code (created_at, id)} sort index and performs no destructive operations.
 */
class MigrationV14SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV14SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V14__wave2_admin_table_indexes.sql")) {
            assertNotNull(in, "V14 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationAddsReceivablesCreatedIndex() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create index ix_receivables_created on receivables (created_at, id)"),
                "V14 must add the receivables (created_at, id) sort index");
    }

    @Test
    void migrationIsAdditiveOnly() throws IOException {
        String sql = migration();
        assertTrue(!sql.contains("drop table") && !sql.contains("drop column")
                        && !sql.contains("drop index"),
                "V14 must be additive only (no drops)");
    }
}
