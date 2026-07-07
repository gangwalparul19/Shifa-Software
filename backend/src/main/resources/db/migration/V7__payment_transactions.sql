-- =============================================================================
-- Shifa Herbal Remedies OMS - online payment transactions (V7, Phase E)
-- Adds a `payment_transactions` table recording each online-payment attempt for
-- an order through a swappable payment gateway (SANDBOX by default, Razorpay
-- stub behind the same interface). One order may have several attempts (a failed
-- attempt followed by a successful one), so this is a 1:N child of `orders`.
--
-- Engine/charset/money conventions match V1-V6 (InnoDB, utf8mb4, DECIMAL(12,2)).
-- All changes are additive and backward compatible: the table is brand new and
-- nothing on existing tables is modified, so existing checkout / COD / coupon /
-- order / account / review behaviour is untouched.
--
--   * order_id           : the order being paid for (FK -> orders.id).
--   * gateway            : provider name snapshot (e.g. SANDBOX / RAZORPAY).
--   * gateway_order_id   : the gateway's order/session id created at initiate.
--   * gateway_payment_id : the gateway's payment id captured at confirm (NULL
--                          until a confirm attempt records one).
--   * amount             : the amount for this attempt (order total), DECIMAL(12,2).
--   * status             : CREATED (session made) | PAID (verified) | FAILED
--                          (verification failed), enforced by a CHECK.
-- =============================================================================

CREATE TABLE payment_transactions (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    order_id           BIGINT        NOT NULL,
    gateway            VARCHAR(32)   NOT NULL,
    gateway_order_id   VARCHAR(128)  NOT NULL,
    gateway_payment_id VARCHAR(128)  NULL,
    amount             DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    status             VARCHAR(16)   NOT NULL,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_payment_transactions PRIMARY KEY (id),
    CONSTRAINT fk_payment_transactions_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_payment_transactions_status CHECK (status IN ('CREATED', 'PAID', 'FAILED')),
    CONSTRAINT ck_payment_transactions_amount CHECK (amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Admin visibility: list an order's transactions newest first.
CREATE INDEX ix_payment_transactions_order ON payment_transactions (order_id);

-- Confirm looks a transaction up by the gateway order id returned at initiate.
CREATE INDEX ix_payment_transactions_gateway_order ON payment_transactions (gateway_order_id);
