package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Definition-level guard for the safe payment UTR preflight/constraint migration. */
class MigrationV104PaymentUtrSmokeTest {

    @Test
    void normalizesAndRecordsHistoricalDuplicatesBeforeConditionalConstraint() throws IOException {
        String sql;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V104__payment_utr_normalization_and_preflight.sql")) {
            assertThat(in).as("V104 payment UTR migration").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        }
        assertThat(sql).contains("add column utr_normalized varchar(80)")
                .contains("update payments")
                .contains("payment_utr_preflight_duplicates")
                .contains("insert into payment_utr_preflight_duplicates")
                .contains("having count(*) > 1")
                .contains("create unique index uq_payments_receiving_account_utr")
                .contains("create index ix_payments_receiving_account_utr_pending")
                .contains("prepare payment_utr_index_statement")
                .doesNotContain("delete from payments")
                .doesNotContain("truncate payments");
    }
}
