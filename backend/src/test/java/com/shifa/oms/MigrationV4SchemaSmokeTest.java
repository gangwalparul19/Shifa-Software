package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V4 Flyway migration (customer accounts).
 *
 * <p>Like the V1/V2/V3 smoke tests, this verifies at the migration-definition
 * level (no Docker/Testcontainers) that the V4 script adds the customer identity
 * columns to {@code users}, creates the {@code customer_addresses} table with its
 * FK/index, and adds the {@code customer_user_id} link + FK/index to
 * {@code orders}, using the shared InnoDB/utf8mb4 conventions.
 */
class MigrationV4SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV4SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V4__customer_accounts.sql")) {
            assertNotNull(in, "V4 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationAddsCustomerIdentityColumnsToUsers() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("alter table users"), "V4 must alter users");
        assertTrue(sql.contains("add column email"), "V4 must add users.email");
        assertTrue(sql.contains("add column mobile"), "V4 must add users.mobile");
        assertTrue(sql.contains("ix_users_mobile"), "V4 must index users.mobile");
    }

    @Test
    void migrationCreatesCustomerAddressesTable() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table customer_addresses"), "V4 must create customer_addresses");
        assertTrue(sql.contains("engine=innodb"), "customer_addresses must use InnoDB");
        assertTrue(sql.contains("charset=utf8mb4"), "customer_addresses must use utf8mb4");
        assertTrue(sql.contains("fk_customer_addresses_user"), "must declare the user FK");
        assertTrue(sql.contains("is_default"), "must have the is_default flag");
        assertTrue(sql.contains("ix_customer_addresses_user"), "must index user_id");
    }

    @Test
    void migrationLinksOrdersToCustomerUser() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("alter table orders"), "V4 must alter orders");
        assertTrue(sql.contains("add column customer_user_id"), "V4 must add orders.customer_user_id");
        assertTrue(sql.contains("fk_orders_customer_user"), "V4 must add the customer FK");
        assertTrue(sql.contains("ix_orders_customer_user"), "V4 must index customer_user_id");
    }
}
