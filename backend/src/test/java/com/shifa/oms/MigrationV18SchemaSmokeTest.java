package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V18 Flyway migration (returns / refunds / RTO workflow).
 * Verifies at the migration-definition level that V18 additively creates the
 * {@code order_returns} table with the expected columns + indexes, and performs
 * no destructive operations.
 */
class MigrationV18SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV18SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V18__order_returns.sql")) {
            assertNotNull(in, "V18 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationCreatesOrderReturnsTable() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table order_returns"),
                "V18 must create the order_returns table");
        assertTrue(sql.contains("order_id") && sql.contains("reason")
                        && sql.contains("status") && sql.contains("refund_amount")
                        && sql.contains("restocked") && sql.contains("created_by")
                        && sql.contains("created_at") && sql.contains("updated_at"),
                "V18 must define the order_returns columns");
        assertTrue(sql.contains("foreign key") && sql.contains("references orders"),
                "V18 must add an order_id FK to orders");
    }

    @Test
    void migrationAddsIndexes() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("ix_order_returns_status_created"),
                "V18 must index (status, created_at)");
        assertTrue(sql.contains("ix_order_returns_order"),
                "V18 must index (order_id)");
    }

    @Test
    void migrationIsAdditiveOnly() throws IOException {
        String sql = migration();
        assertTrue(!sql.contains("drop table") && !sql.contains("drop column")
                        && !sql.contains("drop index"),
                "V18 must be additive only (no drops)");
    }
}
