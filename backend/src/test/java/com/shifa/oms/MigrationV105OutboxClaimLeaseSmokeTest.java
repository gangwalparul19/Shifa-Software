package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Definition-level guard for the additive outbox lease schema. */
class MigrationV105OutboxClaimLeaseSmokeTest {

    @Test
    void addsNullableFencingFieldsAndDueClaimIndexWithoutChangingRows() throws IOException {
        String sql;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V105__outbox_claim_leases.sql")) {
            assertThat(in).as("V105 outbox claim lease migration").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
        }
        assertThat(sql).contains("add column lease_owner varchar(128) null")
                .contains("add column lease_token varchar(64) null")
                .contains("add column lease_until datetime null")
                .contains("create index ix_outbox_claim_due")
                .contains("event_type, status, next_attempt_at, lease_until, created_at, id")
                .doesNotContain("delete from outbox")
                .doesNotContain("truncate outbox");
    }
}
