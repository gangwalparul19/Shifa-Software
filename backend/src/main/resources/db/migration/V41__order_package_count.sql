-- Wave 2 (product-audit roadmap §4.2): multi-pack — how many physical boxes an
-- order ships in. Drives how many label copies are printed. Additive with a
-- default of 1 so existing rows stay single-box.
ALTER TABLE orders
    ADD COLUMN package_count INT NOT NULL DEFAULT 1;
