-- =============================================================================
-- Shifa Herbal Remedies OMS - Audit log / global activity trail (V17)
--
-- "Operations depth" Feature 3: a central record of "who did what, when" across
-- the platform's key mutating actions (order approve/reject, user management,
-- settings update, stock restock/adjust, coupon create/deactivate, ...). Each
-- row captures the resolved actor (best-effort; null when no auth context), the
-- action verb, the target entity, and a short human-readable summary.
--
-- actor_user_id / actor_username are denormalised snapshots so the trail stays
-- readable even if a user is later renamed or removed.
--
-- Engine / charset conventions match V1 (InnoDB, utf8mb4). Additive only.
-- =============================================================================

CREATE TABLE audit_events (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    actor_user_id  BIGINT       NULL,
    actor_username VARCHAR(100) NULL,
    action         VARCHAR(60)  NOT NULL,
    entity_type    VARCHAR(60)  NOT NULL,
    entity_id      VARCHAR(60)  NULL,
    summary        VARCHAR(1000) NULL,
    created_at     DATETIME     NOT NULL,
    CONSTRAINT pk_audit_events PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The default listing is newest-first over the whole trail.
CREATE INDEX ix_audit_events_created ON audit_events (created_at, id);

-- Filter by action verb.
CREATE INDEX ix_audit_events_action ON audit_events (action);

-- Filter by target entity (e.g. all events for ORDER 123).
CREATE INDEX ix_audit_events_entity ON audit_events (entity_type, entity_id);
