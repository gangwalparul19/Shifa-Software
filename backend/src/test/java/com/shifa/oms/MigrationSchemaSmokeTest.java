package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V1 Flyway migration (task 1.3).
 *
 * <p>A full Testcontainers-based apply-and-introspect test requires Docker,
 * which is not available in this environment. This test instead verifies at the
 * migration-definition level that every table, unique constraint, and index
 * mandated by the design is declared in the migration script, guarding against
 * accidental removal. When Docker is available, this can be upgraded to a
 * Testcontainers MySQL apply test.
 */
class MigrationSchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationSchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V1__initial_schema.sql")) {
            assertNotNull(in, "V1 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationDefinesAllTables() throws IOException {
        String sql = migration();
        List<String> tables = List.of(
                "users", "products", "product_images", "orders", "line_items",
                "payments", "status_history", "courier_companies", "courier_records",
                "receivables", "cart_items", "wishlist_items", "outbox", "backup_runs");
        for (String table : tables) {
            assertTrue(sql.contains("create table " + table + " "),
                    "Migration must create table: " + table);
        }
    }

    @Test
    void migrationDefinesRequiredUniqueConstraints() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("unique (username)"), "users.username must be unique");
        assertTrue(sql.contains("unique (sku)"), "products.sku must be unique");
        assertTrue(sql.contains("unique (order_code)"), "orders.order_code must be unique");
        assertTrue(sql.contains("uq_courier_records_order unique (order_id)"),
                "courier_records.order_id must be unique");
        assertTrue(sql.contains("uq_cart_items_customer_product unique (customer_id, product_id)"),
                "cart_items (customer_id, product_id) must be unique");
        assertTrue(sql.contains("uq_wishlist_items_customer_product unique (customer_id, product_id)"),
                "wishlist_items (customer_id, product_id) must be unique");
    }

    @Test
    void migrationDefinesRequiredIndexes() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("on orders (customer_mobile)"), "orders.customer_mobile index required");
        assertTrue(sql.contains("on orders (order_status)"), "orders.order_status index required");
        assertTrue(sql.contains("on orders (created_by)"), "orders.created_by index required");
        assertTrue(sql.contains("on orders (created_at)"), "orders.created_at index required");
        assertTrue(sql.contains("on courier_records (awb)"), "courier_records.awb index required");
    }

    @Test
    void migrationUsesInnoDbAndUtf8mb4AndDecimalMoney() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("engine=innodb"), "tables must use InnoDB");
        assertTrue(sql.contains("charset=utf8mb4"), "tables must use utf8mb4");
        assertTrue(sql.contains("decimal(12,2)"), "monetary columns must be DECIMAL(12,2)");
    }
}
