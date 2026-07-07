package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V15 Flyway migration (Wave 3 invoice numbering + line tax
 * snapshot). Verifies at the migration-definition level that V15 additively adds
 * the settings invoice/bank/GST-slab columns, the invoice_sequence counter, the
 * orders.invoice_number column, and the line_items HSN/GST snapshot columns, and
 * performs no destructive operations.
 */
class MigrationV15SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV15SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V15__wave3_invoice_numbering_and_line_tax.sql")) {
            assertNotNull(in, "V15 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationAddsSettingsInvoiceAndBankColumns() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("add column invoice_number_prefix"),
                "V15 must add app_settings.invoice_number_prefix");
        assertTrue(sql.contains("add column invoice_terms"),
                "V15 must add app_settings.invoice_terms");
        assertTrue(sql.contains("add column bank_name")
                        && sql.contains("add column bank_account_name")
                        && sql.contains("add column bank_account_number")
                        && sql.contains("add column bank_ifsc")
                        && sql.contains("add column bank_branch"),
                "V15 must add the bank-detail columns");
        assertTrue(sql.contains("add column gst_slabs"),
                "V15 must add app_settings.gst_slabs");
    }

    @Test
    void migrationAddsInvoiceSequenceTableAndSeedsIt() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table invoice_sequence"),
                "V15 must create the invoice_sequence counter table");
        assertTrue(sql.contains("insert into invoice_sequence"),
                "V15 must seed the invoice_sequence counter row");
    }

    @Test
    void migrationAddsOrderInvoiceNumberAndLineTaxColumns() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("alter table orders add column invoice_number"),
                "V15 must add orders.invoice_number");
        assertTrue(sql.contains("alter table line_items add column hsn_code"),
                "V15 must add line_items.hsn_code");
        assertTrue(sql.contains("alter table line_items add column gst_rate"),
                "V15 must add line_items.gst_rate");
    }

    @Test
    void migrationIsAdditiveOnly() throws IOException {
        String sql = migration();
        assertTrue(!sql.contains("drop table") && !sql.contains("drop column")
                        && !sql.contains("drop index"),
                "V15 must be additive only (no drops)");
    }
}
