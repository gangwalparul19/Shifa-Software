package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the additive settlement metadata required by B1-B4. */
class MigrationV103SettlementIntegritySmokeTest {

    @Test
    void settlementIntegrityMigrationKeepsCanonicalKeyReasonAndDateDefaultMetadata() throws IOException {
        String sql;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V103__settlement_integrity.sql")) {
            assertThat(in).as("V103 settlement integrity migration").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        }
        assertThat(sql).contains("add column target_key varchar(100)")
                .contains("add column reason varchar(500)")
                .contains("add column date_defaulted boolean")
                .contains("uq_settlement_allocations_line_target_key")
                .contains("ix_settlement_allocations_target_key");
    }
}
