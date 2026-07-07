package com.shifa.oms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the V3 Flyway migration (catalog & discovery).
 *
 * <p>Like the V1/V2 smoke tests, this verifies at the migration-definition level
 * (no Docker/Testcontainers) that the V3 script creates the {@code categories}
 * table, adds the catalog columns to {@code products} with the correct defaults,
 * declares the FK + indexes, and seeds the starter categories.
 */
class MigrationV3SchemaSmokeTest {

    private static String migration() throws IOException {
        try (InputStream in = MigrationV3SchemaSmokeTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V3__catalog_discovery.sql")) {
            assertNotNull(in, "V3 migration script must be present on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void migrationCreatesCategoriesTable() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("create table categories "), "V3 must create categories");
        assertTrue(sql.contains("engine=innodb"), "categories must use InnoDB");
        assertTrue(sql.contains("charset=utf8mb4"), "categories must use utf8mb4");
        assertTrue(sql.contains("uq_categories_name"), "categories.name must be unique");
        assertTrue(sql.contains("uq_categories_slug"), "categories.slug must be unique");
    }

    @Test
    void migrationAddsCatalogColumnsToProducts() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("add column category_id"), "V3 must add products.category_id");
        assertTrue(sql.contains("add column stock_quantity"), "V3 must add products.stock_quantity");
        assertTrue(sql.contains("add column track_inventory"), "V3 must add products.track_inventory");
        assertTrue(sql.contains("add column featured"), "V3 must add products.featured");
    }

    @Test
    void migrationSetsSafeDefaults() throws IOException {
        String sql = migration().replaceAll("[ \\t]+", " ");
        // stock defaults to 0, tracking + featured default to FALSE (legacy-safe).
        assertTrue(sql.contains("stock_quantity int not null default 0"),
                "stock_quantity must default to 0");
        assertTrue(sql.contains("default false"), "track_inventory/featured must default FALSE");
    }

    @Test
    void migrationDeclaresForeignKeyAndIndexes() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("fk_products_category"), "V3 must add the category FK");
        assertTrue(sql.contains("ix_products_category"), "V3 must index products.category_id");
        assertTrue(sql.contains("ix_products_featured"), "V3 must index products.featured");
    }

    @Test
    void migrationSeedsStarterCategories() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("insert into categories"), "V3 must seed categories");
        for (String slug : new String[] {
                "immunity", "digestion", "hair-and-skin", "juices", "churna", "personal-care"}) {
            assertTrue(sql.contains(slug), "V3 must seed category slug: " + slug);
        }
    }
}
