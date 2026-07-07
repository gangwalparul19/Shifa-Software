package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V6 Flyway migration (coupons / discount codes).
 *
 * <p>Like the V1-V5 smoke tests, this verifies at the migration-definition level
 * (no Docker/Testcontainers) that the V6 script creates the {@code coupons}
 * table with its unique code + CHECK constraints and adds the additive
 * {@code coupon_code} / {@code discount_amount} columns to {@code orders}, using
 * the shared InnoDB/utf8mb4 conventions.
 */
class MigrationV6SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV6SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V6__coupons.sql")) {
            assertNotNull(in, "V6 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationCreatesCouponsTable() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table coupons"), "V6 must create coupons");
        assertTrue(sql.contains("engine=innodb"), "coupons must use InnoDB");
        assertTrue(sql.contains("charset=utf8mb4"), "coupons must use utf8mb4");
    }

    @Test
    void migrationDeclaresCodeUniquenessAndTypeConstraint() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("uq_coupons_code"), "must declare a unique code constraint");
        assertTrue(sql.contains("ck_coupons_type"), "must declare the type CHECK");
        assertTrue(sql.contains("'percent'") && sql.contains("'flat'") && sql.contains("'free_shipping'"),
                "type CHECK must allow PERCENT / FLAT / FREE_SHIPPING");
    }

    @Test
    void migrationAddsCouponColumnsToOrders() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("alter table orders"), "must alter the orders table");
        assertTrue(sql.contains("coupon_code"), "orders must gain coupon_code");
        assertTrue(sql.contains("discount_amount"), "orders must gain discount_amount");
        assertTrue(sql.contains("decimal(12,2)"), "discount_amount must be DECIMAL(12,2)");
    }
}
