-- =============================================================================
-- V83 — neutralize hard-coded "Shifa" identity in the SEED data (white-label).
--
-- The demo/catalog seed migrations (V22 / V27) and the courier seeder stamped
-- Shifa-specific names that leak the Shifa brand on a fresh white-label / client
-- deployment:
--   * users.full_name "Shifa Admin"   -> a neutral role name
--   * courier_companies.name "Shifa Express" -> a neutral in-house courier name
--
-- We never edit an already-applied migration (Flyway rule), so this additive
-- migration rewrites those values post-seed. It is idempotent + guarded (only
-- rows that still carry the Shifa value are touched), so it is a no-op on a DB
-- where they were already renamed by hand, and safe on prod (prod simply had
-- "Shifa Admin" -> "Platform Administrator", which is harmless/neutral).
--
-- The seller legal name shown on invoices/labels is NOT touched here: it is
-- runtime config in app_settings (set in Settings / stamped from app.brand.name),
-- so each client controls it without a migration.
-- =============================================================================

UPDATE users
   SET full_name = 'Platform Administrator'
 WHERE full_name = 'Shifa Admin';

UPDATE courier_companies
   SET name = 'In-House Delivery'
 WHERE name = 'Shifa Express';
