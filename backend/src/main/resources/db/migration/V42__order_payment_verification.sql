-- Wave 2 (product-audit roadmap §4.4): payment authenticity verification.
-- An additive layer on the order (does NOT change the order status machine):
-- prepaid/partially-paid orders start PENDING and a Payment Verifier confirms or
-- rejects them. Pure COD orders leave these NULL (nothing to verify).
ALTER TABLE orders
    ADD COLUMN payment_verification_status VARCHAR(20)  NULL,
    ADD COLUMN payment_verified_by         BIGINT       NULL,
    ADD COLUMN payment_verified_at         DATETIME     NULL,
    ADD COLUMN payment_verification_note   VARCHAR(500) NULL;

-- Index the verification queue lookup (pending payments first).
CREATE INDEX ix_orders_payment_verification ON orders (payment_verification_status);
