-- Label redesign feature: capture why an order was marked RTO (returned to
-- origin) when a packer/admin manually marks it by scanning the label, alongside
-- the existing automatic courier-driven RTO edge. Additive & nullable so
-- existing rows and the automatic (courier) path — which does not set a reason
-- today — stay valid.
ALTER TABLE orders
    ADD COLUMN rto_reason      VARCHAR(30)  NULL,
    ADD COLUMN rto_reason_note VARCHAR(500) NULL;
