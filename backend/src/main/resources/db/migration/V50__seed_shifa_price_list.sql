-- Load the official Shifa Herbal Remedies price list (30 products) and restrict
-- the orderable catalog to exactly these items. Idempotent: re-running updates
-- the 30 in place (matched by deterministic SKU) and never creates duplicates.
-- (product-catalog-pricing-gst spec, Requirements 2.x, 3.x)

INSERT INTO products
    (sku, name, description, mrp, sale_price, minimum_rate, hsn_code, wt_ml, gst_rate,
     visibility, stock_quantity, track_inventory, featured)
VALUES
    ('SHIFA-001', 'Beard wash',                        NULL, 1200.00, 1000.00,  600.00, '33059040', '100ML',       5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-002', 'Blue Cream (tiger)',                NULL, 1000.00,  800.00,  600.00, '30049011', '5gm',         5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-003', 'Breath Pure (Rogan Hayat) Rollon',  NULL, 1000.00,  800.00,  600.00, '30049011', '10ml',        5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-004', 'Breath Pure (Rogan Hayat) Droper',  NULL, 1000.00,  800.00,  600.00, '30049011', '10ml',        5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-005', 'Exotic Khajoor',                    NULL,  250.00,  200.00,  150.00, '8041090',  '100gm',       5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-006', 'Eye Drop 10 ML pack',               NULL, 1000.00,  800.00,  600.00, '30049011', '10ml',        5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-007', 'Face Cream',                        NULL, 1200.00, 1000.00,  800.00, '3304',     '80gm',       18.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-008', 'Facewash',                          NULL, 1000.00,  800.00,  600.00, '3304',     '100ml',      18.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-009', 'Farba cream',                       NULL, 1200.00, 1000.00,  800.00, '3004',     '10gm',        5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-010', 'Capsules Power Gold',               NULL,  200.00,  180.00,  140.00, '30049011', '1 Pc',        5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-011', 'Gut Cleanser',                      NULL, 1500.00, 1100.00,  800.00, '12119060', '350gm',       5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-012', 'HAIR OIL',                          NULL, 1499.00,  800.00,  600.00, '3305',     '100ML',      18.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-013', 'Hair oil & Shampoo pack',           NULL, 2600.00, 1400.00, 1200.00, '3305',     'Combo',      18.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-014', 'Hair Shampoo',                      NULL, 1499.00,  800.00,  600.00, '3305',     '100ML',      18.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-015', 'Height Heal',                       NULL, 1500.00, 1100.00,  800.00, '2106',     '350gm',       5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-016', 'Immuno booster',                    NULL, 1500.00, 1100.00,  800.00, '21069099', '350gm',       5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-017', 'Joint Heal',                        NULL, 1500.00, 1100.00,  800.00, '21069099', '150gm',       5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-018', 'joint heal oil',                    NULL, 1500.00, 1100.00,  800.00, '3004',     '100ml',       5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-019', 'Joint heal plus capsule',           NULL, 1500.00, 1100.00,  800.00, '3305',     '30 Pc cpsl',  5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-020', 'Madhumeh',                          NULL, 1950.00, 1450.00, 1100.00, '3004',     '60 Pc cpsl',  5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-021', 'Maqwi Mumsik Majun',                NULL, 1600.00, 1400.00, 1000.00, '3003',     '80gm',        5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-022', 'Marham Shifa',                      NULL, 1000.00,  800.00,  600.00, '3004',     '40gm',        5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-023', 'Mind Cure Syrup',                   NULL, 1950.00, 1450.00, 1100.00, '3004',     '300 ml',      5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-024', 'Ubtun Powder',                      NULL, 1200.00, 1000.00,  800.00, '3304',     '150gm',      18.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-025', 'PET KAM',                           NULL, 1500.00, 1100.00,  800.00, '3004',     '100gm',       5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-026', 'Power Gold tablets',                NULL, 1500.00, 1100.00,  800.00, '30049011', '60 TB PP',    5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-027', 'Semen Booster',                     NULL, 1500.00, 1100.00,  800.00, '21069099', '150gm',       5.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-028', 'Shifa honey Small',                 NULL,  600.00,  450.00,  300.00, '409',      NULL,          0.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-029', 'Shifa honey Large',                 NULL,  699.00,  500.00,  350.00, '409',      NULL,          0.00, 'PUBLISHED', 0, 0, 0),
    ('SHIFA-030', 'Sujan Syrup',                       NULL, 1950.00, 1450.00, 1100.00, '3004',     '300 ml',      5.00, 'PUBLISHED', 0, 0, 0)
ON DUPLICATE KEY UPDATE
    name         = VALUES(name),
    mrp          = VALUES(mrp),
    sale_price   = VALUES(sale_price),
    minimum_rate = VALUES(minimum_rate),
    hsn_code     = VALUES(hsn_code),
    wt_ml        = VALUES(wt_ml),
    gst_rate     = VALUES(gst_rate),
    visibility   = 'PUBLISHED';

-- Restrict the orderable catalog to exactly the 30 products above: every other
-- product is hidden (kept, not deleted, so historical orders/reports resolve).
UPDATE products
SET visibility = 'HIDDEN'
WHERE sku NOT IN (
    'SHIFA-001','SHIFA-002','SHIFA-003','SHIFA-004','SHIFA-005','SHIFA-006','SHIFA-007','SHIFA-008',
    'SHIFA-009','SHIFA-010','SHIFA-011','SHIFA-012','SHIFA-013','SHIFA-014','SHIFA-015','SHIFA-016',
    'SHIFA-017','SHIFA-018','SHIFA-019','SHIFA-020','SHIFA-021','SHIFA-022','SHIFA-023','SHIFA-024',
    'SHIFA-025','SHIFA-026','SHIFA-027','SHIFA-028','SHIFA-029','SHIFA-030'
);
