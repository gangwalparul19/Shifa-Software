-- Config-driven order auto-approval (DEFAULT OFF).
-- When enabled, newly-created low-value, fully-prepaid orders from low-risk
-- customers are auto-approved (skip the manual PENDING_ADMIN_APPROVAL step);
-- high-risk or COD-balance orders always go to manual approval. Additive and
-- safe on existing rows: the flag defaults FALSE so behaviour is unchanged
-- until an admin opts in from Settings.
ALTER TABLE app_settings
    ADD COLUMN auto_approve_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Maximum order total (₹, GST-inclusive) eligible for auto-approval. NULL on
-- legacy rows; the service treats a missing/zero value as "nothing qualifies".
ALTER TABLE app_settings
    ADD COLUMN auto_approve_max_amount DECIMAL(12,2) NULL;
