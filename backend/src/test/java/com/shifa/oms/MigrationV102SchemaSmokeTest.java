package com.shifa.oms;

import com.shifa.oms.auth.AuthSession;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Definition-level smoke checks for the server-authoritative idle-session table. */
class MigrationV102SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV102SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V102__authenticated_sessions.sql")) {
            assertNotNull(in, "V102 migration must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void createsHashedSessionTableWithIdleFieldsAndForeignKey() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table auth_sessions"));
        assertTrue(sql.contains("session_id_hash char(64) not null"));
        assertTrue(sql.contains("user_id bigint not null"));
        assertTrue(sql.contains("session_version bigint not null"));
        assertTrue(sql.contains("created_at datetime not null"));
        assertTrue(sql.contains("last_activity_at datetime not null"));
        assertTrue(sql.contains("revoked_at datetime"));
        assertTrue(sql.contains("foreign key (user_id) references users(id)"));
        assertTrue(sql.contains("unique (session_id_hash)"));
        assertTrue(!sql.contains("session_id varchar"), "raw session ids must not be persisted");
    }

    @Test
    void createsUsefulActivityIndexesAndIsAdditive() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("ix_auth_sessions_user_activity"));
        assertTrue(sql.contains("ix_auth_sessions_active"));
        assertTrue(!sql.contains("drop table") && !sql.contains("drop column"));
    }

    @Test
    void entityMappingsMatchTheMigration() throws Exception {
        assertColumn(AuthSession.class, "sessionIdHash", "session_id_hash", 64, "CHAR(64)");
        assertColumn(AuthSession.class, "userId", "user_id", 0, null);
        assertColumn(AuthSession.class, "sessionVersion", "session_version", 0, null);
        assertColumn(AuthSession.class, "createdAt", "created_at", 0, null);
        assertColumn(AuthSession.class, "lastActivityAt", "last_activity_at", 0, null);
        assertColumn(AuthSession.class, "revokedAt", "revoked_at", 0, null);
    }

    private static void assertColumn(Class<?> type, String fieldName, String name, int length,
                                     String columnDefinition)
            throws NoSuchFieldException {
        Field field = type.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column, fieldName + " must have @Column");
        assertEquals(name, column.name());
        if (length > 0) assertEquals(length, column.length());
        if (columnDefinition != null) assertEquals(columnDefinition, column.columnDefinition(),
                fieldName + " must declare the SQL type explicitly");
    }
}
