-- Wave 1 (product-audit roadmap §4.5): capture an optional alternate contact
-- number on an order, used for failed-delivery follow-up. Additive & nullable so
-- existing rows and seeded data stay valid.
ALTER TABLE orders
    ADD COLUMN alternate_mobile VARCHAR(10) NULL AFTER customer_mobile;
