-- Customer records & internal CRM depth (FEATURE-ROADMAP §1).
--
-- A "customer" in this system is derived data — there is no customers table;
-- customers are aggregated from the `orders` table keyed by `customer_mobile`.
-- This migration adds the first two pieces of PERSISTED per-customer data that
-- cannot be derived from orders:
--
--   * customer_tags  — free-form segments/labels staff attach to a customer
--                      (VIP, wholesale, repeat, city, …). Keyed by mobile.
--   * customer_notes — a staff-authored notes timeline for a customer.
--
-- Both are keyed by the customer's 10-digit mobile (the de-facto customer
-- identity used across the CRM read layer). Additive; safe on seeded data.

CREATE TABLE customer_tags (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    customer_mobile VARCHAR(10)  NOT NULL,
    tag             VARCHAR(40)  NOT NULL,
    created_by      BIGINT       NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY ux_customer_tag (customer_mobile, tag),
    KEY ix_customer_tags_mobile (customer_mobile)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customer_notes (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    customer_mobile VARCHAR(10)   NOT NULL,
    note            VARCHAR(1000) NOT NULL,
    created_by      BIGINT        NULL,
    created_by_name VARCHAR(150)  NULL,
    created_at      DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY ix_customer_notes_mobile (customer_mobile, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
