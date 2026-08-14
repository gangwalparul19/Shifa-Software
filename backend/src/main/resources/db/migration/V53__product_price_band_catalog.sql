-- Product price band + catalog reseed (feature: product price list from
-- 'List of price Sshifa.pdf'). Adds a per-product MINIMUM price and a pack-size
-- label, then reseeds the catalog to exactly the 30 listed products.
--
-- Price band semantics per product:
--   min_price  = Minimum Rate (floor a salesperson may enter)
--   sale_price = Auto Fetch Rate (the default price filled on the order)
--   mrp        = MRP (ceiling a salesperson may enter)
-- hsn_code + gst_rate are set per product from the list.
--
-- Reseed strategy (confirmed with the client, option A = HIDE, non-destructive):
--   1. Hide EVERY existing product (visibility HIDDEN) so the picker is emptied.
--   2. Upsert the 30 listed products by name and (re)PUBLISH them.
-- Products not on the list stay HIDDEN — they vanish from order entry but their
-- past orders/invoices remain intact (no rows are deleted, no FKs broken).
-- Additive columns; safe to run on seeded data.

ALTER TABLE products ADD COLUMN min_price DECIMAL(12,2) NULL AFTER sale_price;
ALTER TABLE products ADD COLUMN pack_size VARCHAR(40) NULL AFTER hsn_code;

-- (1) Empty the published catalog; the 30 below are re-published individually.
UPDATE products SET visibility = 'HIDDEN';

-- (2) Upsert each listed product (UPDATE existing by name, else INSERT), PUBLISHED.
UPDATE products SET mrp=1200.00, sale_price=1000.00, min_price=600.00, hsn_code='33059040', gst_rate=5.00, pack_size='100ML', visibility='PUBLISHED' WHERE name='Beard wash';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-01', 'Beard wash', 1200.00, 1000.00, 600.00, '33059040', 5.00, '100ML', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Beard wash');

UPDATE products SET mrp=1000.00, sale_price=800.00, min_price=600.00, hsn_code='30049011', gst_rate=5.00, pack_size='5gm', visibility='PUBLISHED' WHERE name='Blue Cream (tiger)';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-02', 'Blue Cream (tiger)', 1000.00, 800.00, 600.00, '30049011', 5.00, '5gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Blue Cream (tiger)');

UPDATE products SET mrp=1000.00, sale_price=800.00, min_price=600.00, hsn_code='30049011', gst_rate=5.00, pack_size='10ml', visibility='PUBLISHED' WHERE name='Breath Pure (Rogan Hayat) Rollon';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-03', 'Breath Pure (Rogan Hayat) Rollon', 1000.00, 800.00, 600.00, '30049011', 5.00, '10ml', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Breath Pure (Rogan Hayat) Rollon');

UPDATE products SET mrp=1000.00, sale_price=800.00, min_price=600.00, hsn_code='30049011', gst_rate=5.00, pack_size='10ml', visibility='PUBLISHED' WHERE name='Breath Pure (Rogan Hayat) Droper';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-04', 'Breath Pure (Rogan Hayat) Droper', 1000.00, 800.00, 600.00, '30049011', 5.00, '10ml', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Breath Pure (Rogan Hayat) Droper');

UPDATE products SET mrp=250.00, sale_price=200.00, min_price=150.00, hsn_code='8041090', gst_rate=5.00, pack_size='100gm', visibility='PUBLISHED' WHERE name='Exotic Khajoor';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-05', 'Exotic Khajoor', 250.00, 200.00, 150.00, '8041090', 5.00, '100gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Exotic Khajoor');

UPDATE products SET mrp=1000.00, sale_price=800.00, min_price=600.00, hsn_code='30049011', gst_rate=5.00, pack_size='10ml', visibility='PUBLISHED' WHERE name='Eye Drop 10 ML pack';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-06', 'Eye Drop 10 ML pack', 1000.00, 800.00, 600.00, '30049011', 5.00, '10ml', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Eye Drop 10 ML pack');

UPDATE products SET mrp=1200.00, sale_price=1000.00, min_price=800.00, hsn_code='3304', gst_rate=18.00, pack_size='80gm', visibility='PUBLISHED' WHERE name='Face Cream';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-07', 'Face Cream', 1200.00, 1000.00, 800.00, '3304', 18.00, '80gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Face Cream');

UPDATE products SET mrp=1000.00, sale_price=800.00, min_price=600.00, hsn_code='3304', gst_rate=18.00, pack_size='100ml', visibility='PUBLISHED' WHERE name='Facewash';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-08', 'Facewash', 1000.00, 800.00, 600.00, '3304', 18.00, '100ml', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Facewash');

UPDATE products SET mrp=1200.00, sale_price=1000.00, min_price=800.00, hsn_code='3004', gst_rate=5.00, pack_size='10gm', visibility='PUBLISHED' WHERE name='Farba cream';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-09', 'Farba cream', 1200.00, 1000.00, 800.00, '3004', 5.00, '10gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Farba cream');

UPDATE products SET mrp=200.00, sale_price=180.00, min_price=140.00, hsn_code='30049011', gst_rate=5.00, pack_size='1 Pc', visibility='PUBLISHED' WHERE name='Capsules Power Gold';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-10', 'Capsules Power Gold', 200.00, 180.00, 140.00, '30049011', 5.00, '1 Pc', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Capsules Power Gold');

UPDATE products SET mrp=1500.00, sale_price=1100.00, min_price=800.00, hsn_code='12119060', gst_rate=5.00, pack_size='350gm', visibility='PUBLISHED' WHERE name='Gut Cleanser';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-11', 'Gut Cleanser', 1500.00, 1100.00, 800.00, '12119060', 5.00, '350gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Gut Cleanser');

UPDATE products SET mrp=1499.00, sale_price=800.00, min_price=600.00, hsn_code='3305', gst_rate=18.00, pack_size='100ML', visibility='PUBLISHED' WHERE name='HAIR OIL';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-12', 'HAIR OIL', 1499.00, 800.00, 600.00, '3305', 18.00, '100ML', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='HAIR OIL');

UPDATE products SET mrp=2600.00, sale_price=1400.00, min_price=1200.00, hsn_code='3305', gst_rate=18.00, pack_size='Combo', visibility='PUBLISHED' WHERE name='Hair oil & Shampoo pack';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-13', 'Hair oil & Shampoo pack', 2600.00, 1400.00, 1200.00, '3305', 18.00, 'Combo', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Hair oil & Shampoo pack');

UPDATE products SET mrp=1499.00, sale_price=800.00, min_price=600.00, hsn_code='3305', gst_rate=18.00, pack_size='100ML', visibility='PUBLISHED' WHERE name='Hair Shampoo';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-14', 'Hair Shampoo', 1499.00, 800.00, 600.00, '3305', 18.00, '100ML', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Hair Shampoo');

UPDATE products SET mrp=1500.00, sale_price=1100.00, min_price=800.00, hsn_code='2106', gst_rate=5.00, pack_size='350gm', visibility='PUBLISHED' WHERE name='Height Heal';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-15', 'Height Heal', 1500.00, 1100.00, 800.00, '2106', 5.00, '350gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Height Heal');

UPDATE products SET mrp=1500.00, sale_price=1100.00, min_price=800.00, hsn_code='21069099', gst_rate=5.00, pack_size='350gm', visibility='PUBLISHED' WHERE name='Immuno booster';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-16', 'Immuno booster', 1500.00, 1100.00, 800.00, '21069099', 5.00, '350gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Immuno booster');

UPDATE products SET mrp=1500.00, sale_price=1100.00, min_price=800.00, hsn_code='21069099', gst_rate=5.00, pack_size='150gm', visibility='PUBLISHED' WHERE name='Joint Heal';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-17', 'Joint Heal', 1500.00, 1100.00, 800.00, '21069099', 5.00, '150gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Joint Heal');

UPDATE products SET mrp=1500.00, sale_price=1100.00, min_price=800.00, hsn_code='3004', gst_rate=5.00, pack_size='100ml', visibility='PUBLISHED' WHERE name='joint heal oil';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-18', 'joint heal oil', 1500.00, 1100.00, 800.00, '3004', 5.00, '100ml', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='joint heal oil');

UPDATE products SET mrp=1500.00, sale_price=1100.00, min_price=800.00, hsn_code='3305', gst_rate=5.00, pack_size='30 Pc cpsl', visibility='PUBLISHED' WHERE name='Joint heal plus capsule';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-19', 'Joint heal plus capsule', 1500.00, 1100.00, 800.00, '3305', 5.00, '30 Pc cpsl', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Joint heal plus capsule');

UPDATE products SET mrp=1950.00, sale_price=1450.00, min_price=1100.00, hsn_code='3004', gst_rate=5.00, pack_size='60 Pc cpsl', visibility='PUBLISHED' WHERE name='Madhumeh';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-20', 'Madhumeh', 1950.00, 1450.00, 1100.00, '3004', 5.00, '60 Pc cpsl', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Madhumeh');

UPDATE products SET mrp=1600.00, sale_price=1400.00, min_price=1000.00, hsn_code='3003', gst_rate=5.00, pack_size='80gm', visibility='PUBLISHED' WHERE name='Maqwi Mumsik Majun';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-21', 'Maqwi Mumsik Majun', 1600.00, 1400.00, 1000.00, '3003', 5.00, '80gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Maqwi Mumsik Majun');

UPDATE products SET mrp=1000.00, sale_price=800.00, min_price=600.00, hsn_code='3004', gst_rate=5.00, pack_size='40gm', visibility='PUBLISHED' WHERE name='Marham Shifa';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-22', 'Marham Shifa', 1000.00, 800.00, 600.00, '3004', 5.00, '40gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Marham Shifa');

UPDATE products SET mrp=1950.00, sale_price=1450.00, min_price=1100.00, hsn_code='3004', gst_rate=5.00, pack_size='300 ml', visibility='PUBLISHED' WHERE name='Mind Cure Syrup';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-23', 'Mind Cure Syrup', 1950.00, 1450.00, 1100.00, '3004', 5.00, '300 ml', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Mind Cure Syrup');

UPDATE products SET mrp=1200.00, sale_price=1000.00, min_price=800.00, hsn_code='3304', gst_rate=18.00, pack_size='150gm', visibility='PUBLISHED' WHERE name='Ubtun Powder';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-24', 'Ubtun Powder', 1200.00, 1000.00, 800.00, '3304', 18.00, '150gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Ubtun Powder');

UPDATE products SET mrp=1500.00, sale_price=1100.00, min_price=800.00, hsn_code='3004', gst_rate=5.00, pack_size='100gm', visibility='PUBLISHED' WHERE name='PET KAM';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-25', 'PET KAM', 1500.00, 1100.00, 800.00, '3004', 5.00, '100gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='PET KAM');

UPDATE products SET mrp=1500.00, sale_price=1100.00, min_price=800.00, hsn_code='30049011', gst_rate=5.00, pack_size='60 TB PP', visibility='PUBLISHED' WHERE name='Power Gold tablets';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-26', 'Power Gold tablets', 1500.00, 1100.00, 800.00, '30049011', 5.00, '60 TB PP', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Power Gold tablets');

UPDATE products SET mrp=1500.00, sale_price=1100.00, min_price=800.00, hsn_code='21069099', gst_rate=5.00, pack_size='150gm', visibility='PUBLISHED' WHERE name='Semen Booster';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-27', 'Semen Booster', 1500.00, 1100.00, 800.00, '21069099', 5.00, '150gm', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Semen Booster');

UPDATE products SET mrp=600.00, sale_price=450.00, min_price=300.00, hsn_code='409', gst_rate=0.00, pack_size=NULL, visibility='PUBLISHED' WHERE name='Shifa honey Small';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-28', 'Shifa honey Small', 600.00, 450.00, 300.00, '409', 0.00, NULL, 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Shifa honey Small');

UPDATE products SET mrp=699.00, sale_price=500.00, min_price=350.00, hsn_code='409', gst_rate=0.00, pack_size=NULL, visibility='PUBLISHED' WHERE name='Shifa honey Large';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-29', 'Shifa honey Large', 699.00, 500.00, 350.00, '409', 0.00, NULL, 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Shifa honey Large');

UPDATE products SET mrp=1950.00, sale_price=1450.00, min_price=1100.00, hsn_code='3004', gst_rate=5.00, pack_size='300 ml', visibility='PUBLISHED' WHERE name='Sujan Syrup';
INSERT INTO products (sku, name, mrp, sale_price, min_price, hsn_code, gst_rate, pack_size, visibility)
SELECT 'SHIFA-PL-30', 'Sujan Syrup', 1950.00, 1450.00, 1100.00, '3004', 5.00, '300 ml', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE name='Sujan Syrup');

