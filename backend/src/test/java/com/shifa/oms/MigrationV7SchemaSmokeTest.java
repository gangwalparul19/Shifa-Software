package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V7 Flyway migration (online payment transactions).
 *
 * <p>Like the V1-V6 smoke tests, this verifies at the migration-definition level
 * (no Docker/Testcontainers) that the V7 script creates the
 * {@code payment_transactions} table with its order FK, status CHECK, and money
 * column, using the shared InnoDB/utf8mb4 conventions. It is additive only (no
 * changes to existing tables).
 */
class MigrationV7SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV7SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V7__payment_transactions.sql")) {
            assertNotNull(in, "V7 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationCreatesPaymentTransactionsTable() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table payment_transactions"),
                "V7 must create payment_transactions");
        assertTrue(sql.contains("engine=innodb"), "payment_transactions must use InnoDB");
        assertTrue(sql.contains("charset=utf8mb4"), "payment_transactions must use utf8mb4");
    }

    @Test
    void migrationDeclaresOrderForeignKeyAndStatusCheck() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("fk_payment_transactions_order"),
                "must declare an order foreign key");
        assertTrue(sql.contains("references orders"), "FK must reference orders");
        assertTrue(sql.contains("ck_payment_transactions_status"),
                "must declare the status CHECK");
        assertTrue(sql.contains("'created'") && sql.contains("'paid'") && sql.contains("'failed'"),
                "status CHECK must allow CREATED / PAID / FAILED");
    }

    @Test
    void migrationUsesDecimalMoneyColumn() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("amount") && sql.contains("decimal(12,2)"),
                "amount must be DECIMAL(12,2)");
    }

    @Test
    void migrationIsAdditiveOnly() throws IOException {
        String sql = migration();
        assertTrue(!sql.contains("drop table") && !sql.contains("drop column"),
                "V7 must be additive only (no drops)");
    }
}
