package com.shifa.oms;

import com.shifa.oms.insights.InsightEntity;
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
 * Smoke test for the V26 Flyway migration (Statistical Insights Engine data
 * model, design &sect;Data Model).
 *
 * <p>Consistent with the V1-V25 migration smoke tests, this verifies at the
 * migration-definition level (no Docker/Testcontainers, which are not available
 * in this environment) that:
 *
 * <ul>
 *   <li>V26 creates the {@code insights} table with the design &sect;Data Model
 *       columns, check constraints, and the four indexes (incl. the unique
 *       natural-key index);</li>
 *   <li>the migration is additive only (no drops), so it is safe to apply on the
 *       seeded V22 dataset with no backfill; and</li>
 *   <li>the JPA field&harr;column mappings on {@link InsightEntity} exactly match
 *       the new columns, which is what Hibernate {@code ddl-auto: validate} checks
 *       at startup — a mismatch here is what would fail {@code validate}.</li>
 * </ul>
 */
class MigrationV26SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV26SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V26__insights.sql")) {
            assertNotNull(in, "V26__insights.sql migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void v26CreatesInsightsTableWithExpectedColumns() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table insights"), "V26 must create the insights table");
        assertTrue(sql.contains("insight_type varchar(40) not null"),
                "insights.insight_type VARCHAR(40) NOT NULL");
        assertTrue(sql.contains("scope varchar(20) not null"), "insights.scope VARCHAR(20) NOT NULL");
        assertTrue(sql.contains("scope_ref_id bigint"), "insights.scope_ref_id BIGINT");
        assertTrue(sql.contains("scope_label varchar(200)"), "insights.scope_label VARCHAR(200)");
        assertTrue(sql.contains("severity varchar(20) not null"), "insights.severity VARCHAR(20) NOT NULL");
        assertTrue(sql.contains("title varchar(200) not null"), "insights.title VARCHAR(200) NOT NULL");
        assertTrue(sql.contains("detail text"), "insights.detail TEXT");
        assertTrue(sql.contains("metric_value decimal(18,4)"), "insights.metric_value DECIMAL(18,4)");
        assertTrue(sql.contains("computed_date date not null"), "insights.computed_date DATE NOT NULL");
        assertTrue(sql.contains("dismissed boolean not null default false"),
                "insights.dismissed BOOLEAN NOT NULL DEFAULT FALSE");
        assertTrue(sql.contains("dismissed_at datetime"), "insights.dismissed_at DATETIME");
        assertTrue(sql.contains("dismissed_by bigint"), "insights.dismissed_by BIGINT");
        assertTrue(sql.contains("created_at datetime not null default current_timestamp"),
                "insights.created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
        assertTrue(sql.contains("ck_insights_scope")
                        && sql.contains("in ('global','product','courier','salesperson','order')"),
                "insights.scope CHECK against the five InsightScope names");
        assertTrue(sql.contains("ck_insights_severity")
                        && sql.contains("in ('info','warning','danger')"),
                "insights.severity CHECK against the three InsightSeverity names");
    }

    @Test
    void v26CreatesInsightsIndexes() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("ix_insights_computed_date"), "ix_insights_computed_date (computed_date)");
        assertTrue(sql.contains("ix_insights_type_date"),
                "ix_insights_type_date (insight_type, computed_date)");
        assertTrue(sql.contains("ix_insights_scope"), "ix_insights_scope (scope, scope_ref_id)");
        assertTrue(sql.contains("create unique index ux_insights_natural"),
                "ux_insights_natural UNIQUE (insight_type, scope, scope_ref_id, computed_date)");
    }

    @Test
    void migrationIsAdditiveOnly() throws IOException {
        String sql = migration();
        assertTrue(!sql.contains("drop table") && !sql.contains("drop column")
                        && !sql.contains("drop index"),
                "V26 must be additive only (no drops), safe on the seeded V22 dataset");
    }

    @Test
    void insightEntityMappingsMatchV26Columns() throws Exception {
        assertColumn(InsightEntity.class, "insightType", "insight_type", 40);
        assertColumn(InsightEntity.class, "scope", "scope", 20);
        assertColumn(InsightEntity.class, "scopeLabel", "scope_label", 200);
        assertColumn(InsightEntity.class, "severity", "severity", 20);
        assertColumn(InsightEntity.class, "title", "title", 200);
        // Enum columns persist as their STRING name (matches the VARCHAR columns).
        assertEnumerated(InsightEntity.class, "insightType");
        assertEnumerated(InsightEntity.class, "scope");
        assertEnumerated(InsightEntity.class, "severity");
        // Plain (non-length) columns are present and mapped.
        assertColumnName(InsightEntity.class, "scopeRefId", "scope_ref_id");
        assertColumnName(InsightEntity.class, "metricValue", "metric_value");
        assertColumnName(InsightEntity.class, "computedDate", "computed_date");
        assertColumnName(InsightEntity.class, "dismissed", "dismissed");
        assertColumnName(InsightEntity.class, "dismissedAt", "dismissed_at");
        assertColumnName(InsightEntity.class, "dismissedBy", "dismissed_by");
        assertColumnName(InsightEntity.class, "createdAt", "created_at");
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
