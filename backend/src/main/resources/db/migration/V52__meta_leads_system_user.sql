-- Meta Lead Sync (spec: meta-lead-sync) — provision the dedicated system user
-- that owns auto-imported Meta (Facebook/Instagram) Lead Ads leads until a human
-- reviews and picks them up.
--
-- The password_hash is deliberately NOT a valid BCrypt digest, so
-- BCryptPasswordEncoder.matches() can never succeed → the account has no usable
-- login (Req 12.4). Role SALESPERSON makes the leads it owns behave like ordinary
-- salesperson leads (ADMIN sees all). verification_status = 'VERIFIED' keeps it out
-- of the "pending verification" salespeople queue.
--
-- Guarded insert: safe to run against any DB state and idempotent if re-seeded.
-- Additive; does not modify any previously applied migration. Idempotency of the
-- ingest itself reuses the existing integration_events table (source = 'META'),
-- so no new ingest table is required.
--
-- NOTE: numbered V52 because the shopify-quikshipx-order-sync feature already
-- occupies V49–V51 in this working tree.

INSERT INTO users (username, password_hash, role, full_name, active, verification_status, created_at)
SELECT 'meta-leads', '!LOCKED-NO-LOGIN', 'SALESPERSON', 'Meta Leads (Auto-Import)', 1, 'VERIFIED', NOW()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'meta-leads');
