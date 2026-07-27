-- Wave 1 (product-audit roadmap §4.3): capture who a packed order was handed to
-- at the handover step (courier person / agency name, optional phone), for
-- accountability. Additive & nullable so existing rows stay valid.
ALTER TABLE orders
    ADD COLUMN handover_name  VARCHAR(120) NULL,
    ADD COLUMN handover_phone VARCHAR(10)  NULL;
