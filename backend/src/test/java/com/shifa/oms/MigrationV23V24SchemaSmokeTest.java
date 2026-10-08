package com.shifa.oms;

import com.shifa.oms.adminnotification.AdminNotification;
import com.shifa.oms.order.OrderEntity;
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
 * Smoke test for the V23 / V24 Flyway migrations (Role-Based Order Workflow
 * data model, design §3.1 / §3.3 / §3.4).
 *
 * <p>Consistent with the V1-V18 migration smoke tests, this verifies at the
 * migration-definition level (no Docker/Testcontainers, which are not available
 * in this environment) that:
 *
 * <ul>
 *   <li>V23 additively adds {@code orders.lead_source}, {@code lead_source_note},
 *       {@code customer_email} and the {@code ix_orders_lead_source} index;</li>
 *   <li>V24 additively adds {@code admin_notifications.recipient_role},
 *       {@code recipient_user_id} and the {@code ix_admin_notifications_recipient}
 *       index;</li>
 *   <li>both migrations are additive only (no drops), so they are safe to apply
 *       on the seeded V22 dataset with no backfill; and</li>
 *   <li>the JPA field&harr;column mappings on {@link OrderEntity} and
 *       {@link AdminNotification} exactly match the new columns, which is what
 *       Hibernate {@code ddl-auto: validate} checks at startup — a mismatch here
 *       is what would fail {@code validate}.</li>
 * </ul>
 *
 * <p>A full apply-and-{@code validate} run against MySQL 8 (source V1..V24 in
 * order, then Hibernate {@code validate}) is performed out-of-band against a
 * scratch database; this test guards the same guarantees in CI without Docker.
 */
class MigrationV23V24SchemaSmokeTest {

    private static String migration(String resource) throws IOException {
        try (InputStream in = MigrationV23V24SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/" + resource)) {
            assertNotNull(in, resource + " migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void v23AddsOrderLeadSourceAndCustomerEmail() throws IOException {
        String sql = migration("V23__order_lead_source_and_customer_email.sql");
        assertTrue(sql.contains("alter table orders add column lead_source      varchar(20)")
                        || sql.contains("alter table orders add column lead_source varchar(20)"),
                "V23 must add orders.lead_source VARCHAR(20)");
        assertTrue(sql.contains("lead_source_note") && sql.contains("varchar(200)"),
                "V23 must add orders.lead_source_note VARCHAR(200)");
        assertTrue(sql.contains("customer_email") && sql.contains("varchar(150)"),
                "V23 must add orders.customer_email VARCHAR(150)");
        assertTrue(sql.contains("ix_orders_lead_source"),
                "V23 must create the ix_orders_lead_source index");
    }

    @Test
    void v24AddsAdminNotificationRecipientAddressing() throws IOException {
        String sql = migration("V24__admin_notification_recipient_addressing.sql");
        assertTrue(sql.contains("recipient_role") && sql.contains("varchar(20)"),
                "V24 must add admin_notifications.recipient_role VARCHAR(20)");
        assertTrue(sql.contains("recipient_user_id") && sql.contains("bigint"),
                "V24 must add admin_notifications.recipient_user_id BIGINT");
        assertTrue(sql.contains("ix_admin_notifications_recipient"),
                "V24 must create the ix_admin_notifications_recipient index");
        assertTrue(sql.contains("(recipient_role, recipient_user_id, read_flag)"),
                "V24 index must cover (recipient_role, recipient_user_id, read_flag)");
    }

    @Test
    void migrationsAreAdditiveOnly() throws IOException {
        for (String resource : new String[]{
                "V23__order_lead_source_and_customer_email.sql",
                "V24__admin_notification_recipient_addressing.sql"}) {
            String sql = migration(resource);
            assertTrue(!sql.contains("drop table") && !sql.contains("drop column")
                            && !sql.contains("drop index"),
                    resource + " must be additive only (no drops), safe on the seeded V22 dataset");
        }
    }

    @Test
    void orderEntityMappingsMatchV23Columns() throws Exception {
        assertColumn(OrderEntity.class, "leadSource", "lead_source", 20, true);
        assertColumn(OrderEntity.class, "leadSourceNote", "lead_source_note", 200, false);
        assertColumn(OrderEntity.class, "customerEmail", "customer_email", 150, false);
        // lead_source is an enum persisted as its STRING name (matches VARCHAR(20)).
        assertTrue(OrderEntity.class.getDeclaredField("leadSource").isAnnotationPresent(Enumerated.class),
                "leadSource must be @Enumerated so it maps to VARCHAR(20)");
    }

    @Test
    void adminNotificationMappingsMatchV24Columns() throws Exception {
        assertColumn(AdminNotification.class, "recipientRole", "recipient_role", 20, true);
        // recipient_user_id is a plain BIGINT column; length is irrelevant.
        Field userId = AdminNotification.class.getDeclaredField("recipientUserId");
        Column col = userId.getAnnotation(Column.class);
        assertNotNull(col, "recipientUserId must be @Column mapped");
        assertEquals("recipient_user_id", col.name());
        assertTrue(AdminNotification.class.getDeclaredField("recipientRole").isAnnotationPresent(Enumerated.class),
                "recipientRole must be @Enumerated so it maps to VARCHAR(20)");
    }

    private static void assertColumn(Class<?> type, String fieldName, String columnName,
                                     int length, boolean enumerated) throws NoSuchFieldException {
        Field field = type.getDeclaredField(fieldName);
        Column col = field.getAnnotation(Column.class);
        assertNotNull(col, fieldName + " must carry a @Column mapping");
        assertEquals(columnName, col.name(), fieldName + " must map to column " + columnName);
        assertEquals(length, col.length(), columnName + " length must match the migration");
    }
}
