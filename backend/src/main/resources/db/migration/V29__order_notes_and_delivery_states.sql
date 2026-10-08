-- =============================================================================
-- Shifa Herbal Remedies OMS - order notes + delivery states (V29)
--
-- Two additive, backward-compatible changes for the salesperson order-entry
-- redesign:
--
--  * orders.notes     - an optional free-text note the salesperson can attach to
--                        a new order (e.g. a specific customer ask), captured on
--                        the New Order form just before saving. Nullable so every
--                        pre-existing/seeded order stays valid with no backfill.
--
--  * delivery_states  - the master list of Indian states / union territories that
--                        drives the typeahead on the New Order address form. It is
--                        managed from the admin Settings page (add / rename /
--                        enable-disable / remove) and seeded once here so the list
--                        is usable immediately. `active` toggles visibility in the
--                        picker without deleting history; `sort_order` gives an
--                        optional manual ordering (falls back to name).
--
-- Additive only (new column + new table + seed). V28 is the previous highest
-- version; never edit an applied migration. Conventions match V1 (InnoDB, utf8mb4).
-- =============================================================================

ALTER TABLE orders
  ADD COLUMN notes VARCHAR(1000) NULL AFTER lead_source_note;

CREATE TABLE delivery_states (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT pk_delivery_states PRIMARY KEY (id),
  CONSTRAINT ux_delivery_states_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One-time seed: 28 states + 8 union territories (all active).
INSERT INTO delivery_states (name, active, sort_order) VALUES
  ('Andhra Pradesh', 1, 0),
  ('Arunachal Pradesh', 1, 0),
  ('Assam', 1, 0),
  ('Bihar', 1, 0),
  ('Chhattisgarh', 1, 0),
  ('Goa', 1, 0),
  ('Gujarat', 1, 0),
  ('Haryana', 1, 0),
  ('Himachal Pradesh', 1, 0),
  ('Jharkhand', 1, 0),
  ('Karnataka', 1, 0),
  ('Kerala', 1, 0),
  ('Madhya Pradesh', 1, 0),
  ('Maharashtra', 1, 0),
  ('Manipur', 1, 0),
  ('Meghalaya', 1, 0),
  ('Mizoram', 1, 0),
  ('Nagaland', 1, 0),
  ('Odisha', 1, 0),
  ('Punjab', 1, 0),
  ('Rajasthan', 1, 0),
  ('Sikkim', 1, 0),
  ('Tamil Nadu', 1, 0),
  ('Telangana', 1, 0),
  ('Tripura', 1, 0),
  ('Uttar Pradesh', 1, 0),
  ('Uttarakhand', 1, 0),
  ('West Bengal', 1, 0),
  ('Andaman and Nicobar Islands', 1, 0),
  ('Chandigarh', 1, 0),
  ('Dadra and Nagar Haveli and Daman and Diu', 1, 0),
  ('Delhi', 1, 0),
  ('Jammu and Kashmir', 1, 0),
  ('Ladakh', 1, 0),
  ('Lakshadweep', 1, 0),
  ('Puducherry', 1, 0);
