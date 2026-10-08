-- Data fix: clamp future-dated orders back into the past.
--
-- Some seeded/imported orders ended up with a `created_at` in the FUTURE, which
-- is impossible and breaks date-sorted views (packing/orders). This migration
-- runs once on startup and pulls any future-dated order back to within the last
-- 24 hours, spread by id so rows keep a stable, distinct ordering rather than
-- bunching at a single instant. Idempotent: rows already in the past are untouched.
UPDATE orders
SET created_at = DATE_SUB(NOW(), INTERVAL (id MOD 1440) MINUTE)
WHERE created_at > NOW();

-- Keep updated_at sane (>= created_at, never in the future). If the column has
-- ON UPDATE CURRENT_TIMESTAMP it is already corrected by the update above; this
-- guards the case where it does not.
UPDATE orders
SET updated_at = created_at
WHERE updated_at IS NOT NULL AND updated_at > NOW();
