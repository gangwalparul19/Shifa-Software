package com.shifa.oms;

import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadStatusHistory;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V25 Flyway migration (Lead Management data model, design
 * §3.2, §3.3, §3.4).
 *
 * <p>Consistent with the V1-V24 migration smoke tests, this verifies at the
 * migration-definition level (no Docker/Testcontainers, which are not available
 * in this environment) that:
 *
 * <ul>
 *   <li>V25 creates the {@code leads} and {@code lead_status_history} tables with
 *       the design §3.2 / §3.3 columns and the §3.4 indexes;</li>
 *   <li>the migration is additive only (no drops), so it is safe to apply on the
 *       seeded V22 dataset with no backfill; and</li>
 *   <li>the JPA field&harr;column mappings on {@link LeadEntity} and
 *       {@link LeadStatusHistory} exactly match the new columns, which is what
 *       Hibernate {@code ddl-auto: validate} checks at startup — a mismatch here
 *       is what would fail {@code validate}.</li>
 * </ul>
 */
class MigrationV25SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV25SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V25__leads.sql")) {
            assertNotNull(in, "V25__leads.sql migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void v25CreatesLeadsTableWithExpectedColumns() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table leads"), "V25 must create the leads table");
        assertTrue(sql.contains("customer_name varchar(120) not null"),
                "leads.customer_name VARCHAR(120) NOT NULL");
        assertTrue(sql.contains("customer_mobile varchar(10)"), "leads.customer_mobile VARCHAR(10)");
        assertTrue(sql.contains("customer_email varchar(150)"), "leads.customer_email VARCHAR(150)");
        assertTrue(sql.contains("lead_source varchar(20) not null"),
                "leads.lead_source VARCHAR(20) NOT NULL");
        assertTrue(sql.contains("lead_source_note varchar(200)"), "leads.lead_source_note VARCHAR(200)");
        assertTrue(sql.contains("status varchar(16) not null default 'new'"),
                "leads.status VARCHAR(16) NOT NULL DEFAULT 'NEW'");
        assertTrue(sql.contains("lost_reason varchar(20)"), "leads.lost_reason VARCHAR(20)");
        assertTrue(sql.contains("lost_reason_note varchar(200)"), "leads.lost_reason_note VARCHAR(200)");
        assertTrue(sql.contains("note varchar(1000)"), "leads.note VARCHAR(1000)");
        assertTrue(sql.contains("follow_up_date date"), "leads.follow_up_date DATE");
        assertTrue(sql.contains("reminded_on date"), "leads.reminded_on DATE");
        assertTrue(sql.contains("owner_user_id bigint not null"), "leads.owner_user_id BIGINT NOT NULL");
        assertTrue(sql.contains("converted_order_id bigint"), "leads.converted_order_id BIGINT");
        assertTrue(sql.contains("references users (id)"), "owner FK → users(id)");
        assertTrue(sql.contains("references orders (id)"), "converted-order FK → orders(id)");
        assertTrue(sql.contains("ck_leads_status")
                        && sql.contains("in ('new','contacted','quoted','won','lost')"),
                "leads.status CHECK against the five LeadStatus names");
    }

    @Test
    void v25CreatesLeadsIndexes() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("ix_leads_owner_status"), "ix_leads_owner_status (owner_user_id, status)");
        assertTrue(sql.contains("ix_leads_status"), "ix_leads_status (status)");
        assertTrue(sql.contains("ix_leads_follow_up"), "ix_leads_follow_up (follow_up_date, status)");
        assertTrue(sql.contains("ix_leads_source"), "ix_leads_source (lead_source)");
        assertTrue(sql.contains("ix_leads_mobile"), "ix_leads_mobile (customer_mobile)");
    }

    @Test
    void v25CreatesLeadStatusHistoryTable() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table lead_status_history"),
                "V25 must create the lead_status_history table");
        assertTrue(sql.contains("lead_id bigint not null"), "lead_status_history.lead_id BIGINT NOT NULL");
        assertTrue(sql.contains("from_status varchar(16)"), "lead_status_history.from_status VARCHAR(16)");
        assertTrue(sql.contains("to_status varchar(16) not null"),
                "lead_status_history.to_status VARCHAR(16) NOT NULL");
        assertTrue(sql.contains("actor varchar(100) not null"),
                "lead_status_history.actor VARCHAR(100) NOT NULL");
        assertTrue(sql.contains("fk_lead_history_lead"), "history FK → leads(id)");
        assertTrue(sql.contains("ix_lead_history_lead"), "ix_lead_history_lead (lead_id)");
    }

    @Test
    void migrationIsAdditiveOnly() throws IOException {
        String sql = migration();
        assertTrue(!sql.contains("drop table") && !sql.contains("drop column")
                        && !sql.contains("drop index"),
                "V25 must be additive only (no drops), safe on the seeded V22 dataset");
    }

    @Test
    void leadEntityMappingsMatchV25Columns() throws Exception {
        assertColumn(LeadEntity.class, "customerName", "customer_name", 120);
        assertColumn(LeadEntity.class, "customerMobile", "customer_mobile", 10);
        assertColumn(LeadEntity.class, "customerEmail", "customer_email", 150);
        assertColumn(LeadEntity.class, "leadSource", "lead_source", 20);
        assertColumn(LeadEntity.class, "leadSourceNote", "lead_source_note", 200);
        assertColumn(LeadEntity.class, "status", "status", 16);
        assertColumn(LeadEntity.class, "lostReason", "lost_reason", 20);
        assertColumn(LeadEntity.class, "lostReasonNote", "lost_reason_note", 200);
        assertColumn(LeadEntity.class, "note", "note", 1000);
        // Enum columns persist as their STRING name (matches the VARCHAR columns).
        assertEnumerated(LeadEntity.class, "leadSource");
        assertEnumerated(LeadEntity.class, "status");
        assertEnumerated(LeadEntity.class, "lostReason");
        // Plain (non-length) columns are present and mapped.
        assertColumnName(LeadEntity.class, "followUpDate", "follow_up_date");
        assertColumnName(LeadEntity.class, "remindedOn", "reminded_on");
        assertColumnName(LeadEntity.class, "ownerUserId", "owner_user_id");
        assertColumnName(LeadEntity.class, "convertedOrderId", "converted_order_id");
    }

    @Test
    void leadStatusHistoryMappingsMatchV25Columns() throws Exception {
        assertColumn(LeadStatusHistory.class, "fromStatus", "from_status", 16);
        assertColumn(LeadStatusHistory.class, "toStatus", "to_status", 16);
        assertColumn(LeadStatusHistory.class, "actor", "actor", 100);
        assertEnumerated(LeadStatusHistory.class, "fromStatus");
        assertEnumerated(LeadStatusHistory.class, "toStatus");
        assertColumnName(LeadStatusHistory.class, "leadId", "lead_id");
    }

    private static void assertColumn(Class<?> type, String fieldName, String columnName, int length)
            throws NoSuchFieldException {
        Field field = type.getDeclaredField(fieldName);
        Column col = field.getAnnotation(Column.class);
        assertNotNull(col, fieldName + " must carry a @Column mapping");
        assertEquals(columnName, col.name(), fieldName + " must map to column " + columnName);
        assertEquals(length, col.length(), columnName + " length must match the migration");
    }

    private static void assertColumnName(Class<?> type, String fieldName, String columnName)
            throws NoSuchFieldException {
        Field field = type.getDeclaredField(fieldName);
        Column col = field.getAnnotation(Column.class);
        assertNotNull(col, fieldName + " must carry a @Column mapping");
        assertEquals(columnName, col.name(), fieldName + " must map to column " + columnName);
    }

    private static void assertEnumerated(Class<?> type, String fieldName) throws NoSuchFieldException {
        assertTrue(type.getDeclaredField(fieldName).isAnnotationPresent(Enumerated.class),
                fieldName + " must be @Enumerated so it maps to its VARCHAR column");
    }
}
