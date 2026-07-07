package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V11 Flyway migration (inventory / stock movements).
 *
 * <p>Like the V1-V10 smoke tests, this verifies at the migration-definition
 * level (no Docker/Testcontainers) that V11 creates the {@code stock_movements}
 * ledger with its product FK + type CHECK and adds the optional per-product
 * {@code low_stock_threshold} column, using the shared InnoDB/utf8mb4
 * conventions.
 */
class MigrationV11SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV11SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V11__stock_movements.sql")) {
            assertNotNull(in, "V11 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationCreatesStockMovementsTable() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table stock_movements"), "V11 must create stock_movements");
        assertTrue(sql.contains("engine=innodb"), "stock_movements must use InnoDB");
        assertTrue(sql.contains("charset=utf8mb4"), "stock_movements must use utf8mb4");
    }

    @Test
    void migrationDeclaresProductForeignKeyAndTypeCheck() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("fk_stock_movements_product"), "must declare a product foreign key");
        assertTrue(sql.contains("references products"), "FK must reference products");
        assertTrue(sql.contains("ck_stock_movements_type"), "must declare the type CHECK");
        assertTrue(sql.contains("'restock'") && sql.contains("'adjustment'")
                        && sql.contains("'sale'") && sql.contains("'return'"),
                "type CHECK must allow RESTOCK / ADJUSTMENT / SALE / RETURN");
    }

    @Test
    void migrationAddsLowStockThresholdColumn() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("alter table products add column low_stock_threshold"),
                "V11 must add products.low_stock_threshold");
    }

    @Test
    void migrationIsAdditiveOnly() throws IOException {
        String sql = migration();
        assertTrue(!sql.contains("drop table") && !sql.contains("drop column"),
                "V11 must be additive only (no drops)");
    }
}
