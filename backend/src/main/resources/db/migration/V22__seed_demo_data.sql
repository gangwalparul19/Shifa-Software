-- =============================================================================
-- Shifa Herbal Remedies OMS - DEMO seed data (V22)
--
-- Populates a realistic, client-ready DEMO dataset so a freshly-migrated
-- database (local dev OR the OCI prod demo) shows meaningful data across every
-- major admin-dashboard table. This migration is intentionally SELF-CONTAINED
-- (plain SQL, no application code) so it runs in ALL Spring profiles, including
-- prod where the @Profile("local") Java seeders never run.
--
-- Alignment with the local-only Java seeders (so they no-op after this seed):
--   * CatalogSeeder skips entirely once there are >= 8 published products; this
--     seeds 13 published products, so it skips.
--   * CourierCompanySeeder skips when a company named "Shifa Express" exists;
--     this seeds exactly that company.
--   * AdminUserSeeder skips usernames it already finds; this seeds "admin" and
--     "packer" with the real bcrypt hashes (admin123 / packer123).
--
-- Ordering: parent rows are inserted before the children that reference them.
-- Explicit ids are used for parents (users, products, suppliers, purchase
-- orders, courier company, orders) so foreign keys are deterministic.
-- Monetary columns follow the V1 DECIMAL(12,2) convention.
-- =============================================================================

-- --------------------------------------------------------- courier company ---
-- Named exactly "Shifa Express" so CourierCompanySeeder (local) no-ops, with the
-- same tracking_url_template the seeder uses.
INSERT INTO courier_companies (id, name, tracking_url_template) VALUES
    (1, 'Shifa Express', 'https://track.example.com/{awb}');

-- ------------------------------------------------------------------ users -----
-- Staff accounts. Real bcrypt hashes reused from the reference DB:
--   admin  -> admin123   |   packer -> packer123
-- The accountant + salespersons reuse the admin hash (password admin123) — fine
-- for a demo. Usernames "admin"/"packer" match AdminUserSeeder so it no-ops.
INSERT INTO users (id, username, password_hash, role, full_name, email, mobile, active, created_at) VALUES
    (1, 'admin',      '$2a$10$jo3eJN7IJ17yFNU9Qa2jsOesiIQeCwXXT6IVUeGt5wkasfjSzq2fi', 'ADMIN',        'Platform Administrator', NULL,                  NULL,         1, '2026-07-01 09:00:00'),
    (2, 'accountant', '$2a$10$jo3eJN7IJ17yFNU9Qa2jsOesiIQeCwXXT6IVUeGt5wkasfjSzq2fi', 'ACCOUNTANT',   'Anjali Deshmukh',        'anjali@shifa.example', NULL,         1, '2026-07-01 09:05:00'),
    (3, 'sales1',     '$2a$10$jo3eJN7IJ17yFNU9Qa2jsOesiIQeCwXXT6IVUeGt5wkasfjSzq2fi', 'SALESPERSON',  'Ravi Teja',              'ravi@shifa.example',   '9876500001', 1, '2026-07-01 09:10:00'),
    (4, 'sales2',     '$2a$10$jo3eJN7IJ17yFNU9Qa2jsOesiIQeCwXXT6IVUeGt5wkasfjSzq2fi', 'SALESPERSON',  'Pooja Bhatt',            'pooja@shifa.example',  '9876500002', 1, '2026-07-01 09:15:00'),
    (5, 'packer',     '$2a$10$sTCO3pcUWNssOg0.IAcxPuoBOj3ppyVCbuy7zThUxQIa/mXNYkZFG', 'PACKING_USER', 'Packing Department',     NULL,                  NULL,         1, '2026-07-01 09:20:00');

-- --------------------------------------------------------------- products -----
-- 13 published products (categories 1..6 already seeded by V3). Real rows reused
-- from the reference catalog. The mix includes low-stock, out-of-stock and
-- untracked (always in-stock) examples for dashboard richness.
INSERT INTO products (id, sku, name, description, mrp, sale_price, hsn_code, gst_rate, visibility, category_id, stock_quantity, track_inventory, low_stock_threshold, featured, created_at, updated_at) VALUES
(1,'SHF-ASH-001','Ashwagandha Herbal Capsules','Pure ashwagandha root extract capsules.',599.00,499.00,NULL,NULL,'PUBLISHED',NULL,0,0,NULL,0,'2026-07-03 15:16:14','2026-07-03 15:16:14'),
(2,'SHR-ASHW-60','Ashwagandha Capsules (60 ct)','Pure Withania somnifera root extract to help the body adapt to stress, support restful sleep, and sustain natural energy and stamina.',799.00,549.00,NULL,NULL,'PUBLISHED',1,39,1,NULL,1,'2026-07-03 16:02:24','2026-07-06 21:53:44'),
(3,'SHR-TRIP-200','Triphala Churna (200 g)','A classic blend of Amla, Haritaki and Bibhitaki that gently supports digestion, regularity and natural detoxification.',349.00,249.00,NULL,NULL,'PUBLISHED',5,25,1,NULL,0,'2026-07-03 16:02:24','2026-07-04 19:56:04'),
(4,'SHR-BRAH-100','Brahmi Hair Oil (100 ml)','Cold-infused Brahmi and Bhringraj in a coconut-sesame base to nourish the scalp, reduce hair fall and promote thicker, shinier hair.',399.00,299.00,NULL,NULL,'PUBLISHED',3,2,1,NULL,1,'2026-07-03 16:02:24','2026-07-06 21:53:44'),
(5,'SHR-GILO-500','Giloy Juice (500 ml)','Fresh-pressed Giloy (Guduchi) juice, a time-honoured immunity builder that supports the body natural defences and healthy metabolism.',299.00,219.00,NULL,NULL,'PUBLISHED',4,60,1,NULL,0,'2026-07-03 16:02:24','2026-07-04 19:56:04'),
(6,'SHR-CHYA-500','Chyawanprash (500 g)','A rich Amla-based rejuvenating jam with over 40 herbs and pure cow ghee to strengthen immunity, vitality and respiratory health.',545.00,399.00,NULL,NULL,'PUBLISHED',1,17,1,NULL,1,'2026-07-03 16:02:24','2026-07-06 21:53:44'),
(7,'SHR-NEEM-150','Neem & Tulsi Face Wash (150 ml)','A gentle sulphate-free cleanser with Neem and Tulsi that clears excess oil, fights blemishes and leaves skin fresh and balanced.',299.00,199.00,NULL,NULL,'PUBLISHED',6,0,1,NULL,0,'2026-07-03 16:02:24','2026-07-04 19:56:04'),
(8,'SHR-AMLA-500','Amla Juice (500 ml)','Cold-pressed Indian Gooseberry juice packed with natural Vitamin C to support immunity, glowing skin and healthy hair.',279.00,199.00,NULL,NULL,'PUBLISHED',4,0,0,NULL,0,'2026-07-03 16:02:24','2026-07-04 19:56:04'),
(9,'SHR-SHIL-20','Shilajit Resin (20 g)','Purified Himalayan Shilajit resin rich in fulvic acid and trace minerals to support stamina, strength and vitality.',1299.00,999.00,NULL,NULL,'PUBLISHED',1,12,1,NULL,1,'2026-07-03 16:02:24','2026-07-04 19:56:04'),
(10,'SHR-KADH-100','Herbal Immunity Kadha (100 g)','A ready-to-brew blend of Tulsi, Ginger, Mulethi, Cinnamon and black pepper for a warming daily immunity drink.',349.00,259.00,NULL,NULL,'PUBLISHED',1,30,1,NULL,0,'2026-07-03 16:02:24','2026-07-04 19:56:04'),
(11,'SHR-ALOE-200','Aloe Vera Gel (200 ml)','Multipurpose Aloe Vera gel to soothe, hydrate and calm skin and hair. Light, non-sticky and free from parabens and artificial colour.',299.00,199.00,NULL,NULL,'PUBLISHED',6,4,1,NULL,0,'2026-07-03 16:02:24','2026-07-06 21:53:44'),
(12,'SHR-MORI-100','Moringa Powder (100 g)','Nutrient-dense Moringa leaf powder, a natural source of plant protein, iron and antioxidants to support daily wellness and energy.',399.00,289.00,NULL,NULL,'PUBLISHED',2,22,1,NULL,0,'2026-07-03 16:02:24','2026-07-04 19:56:05'),
(13,'SHR-KARE-500','Karela Jamun Juice (500 ml)','A bitter-gourd and jamun blend traditionally used to support healthy blood sugar levels and metabolism.',299.00,225.00,NULL,NULL,'PUBLISHED',4,0,0,NULL,0,'2026-07-03 16:02:24','2026-07-04 19:56:05');

-- ---------------------------------------------------------- product_images ---
-- Columns per V1: (id, product_id, object_key, published, sort_order).
INSERT INTO product_images (id, product_id, object_key, published, sort_order) VALUES
(1,2,'products/shifa-01.jpg',1,0),
(2,3,'products/shifa-02.jpg',1,0),
(3,4,'products/shifa-03.jpg',1,0),
(4,5,'products/shifa-04.jpg',1,0),
(5,6,'products/shifa-05.jpg',1,0),
(6,7,'products/shifa-06.jpg',1,0),
(7,8,'products/shifa-07.jpg',1,0),
(8,9,'products/shifa-08.jpg',1,0),
(9,10,'products/shifa-09.jpg',1,0),
(10,11,'products/shifa-10.jpg',1,0),
(11,12,'products/shifa-11.jpg',1,0),
(12,13,'products/shifa-12.jpg',1,0);

-- --------------------------------------------------------------- suppliers ---
INSERT INTO suppliers (id, name, contact_person, phone, email, address, active, created_at) VALUES
(1, 'Himalaya Herbals Pvt Ltd', 'Rajesh Kumar', '9811100001', 'rajesh@himalayaherbals.example', 'Plot 4, MIDC, Nashik, Maharashtra', 1, '2026-07-05 10:00:00'),
(2, 'Patanjali Ayurved Ltd',    'Sunita Devi',  '9811100002', 'procurement@patanjali.example', 'Padartha, Haridwar, Uttarakhand',  1, '2026-07-05 10:05:00'),
(3, 'Organic India Supplies',   'Anil Mehta',   '9811100003', 'sales@organicindia.example',    'Sushant Golf City, Lucknow, UP',   1, '2026-07-05 10:10:00'),
(4, 'Dabur Wholesale',          'Kiran Shah',   '9811100004', 'wholesale@dabur.example',       'Kaushambi, Ghaziabad, UP',         1, '2026-07-05 10:15:00'),
(5, 'Local Herbs Trading Co',   'Ramesh Yadav', '9811100005', 'ramesh@localherbs.example',     'Sanwer Road, Indore, MP',          0, '2026-07-05 10:20:00');

-- ---------------------------------------------------------- purchase_orders ---
-- PO lifecycle spread: RECEIVED, ORDERED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED.
-- po_number is the human-readable sequence PO-0001..PO-0005 (see sequence bump below).
INSERT INTO purchase_orders (id, po_number, supplier_id, status, notes, total_amount, created_by, created_at, received_at) VALUES
(1, 'PO-0001', 1, 'RECEIVED',           'Immunity restock - fully received',    22500.00, 1, '2026-07-20 11:00:00', '2026-07-27 14:30:00'),
(2, 'PO-0002', 2, 'ORDERED',            'Shilajit + Moringa - awaiting goods',  18000.00, 1, '2026-08-02 11:00:00', NULL),
(3, 'PO-0003', 3, 'PARTIALLY_RECEIVED', 'Juices + churna - partial receipt',    12800.00, 1, '2026-08-10 11:00:00', NULL),
(4, 'PO-0004', 4, 'RECEIVED',           'Kadha restock',                         7500.00, 1, '2026-08-18 11:00:00', '2026-08-22 12:00:00'),
(5, 'PO-0005', 5, 'CANCELLED',          'Face wash order cancelled - supplier out of stock', 2250.00, 1, '2026-08-25 11:00:00', NULL);

-- ------------------------------------------------------ purchase_order_items --
INSERT INTO purchase_order_items (purchase_order_id, product_id, quantity, unit_cost, received_quantity) VALUES
(1, 2, 50, 300.00, 50),
(1, 6, 30, 250.00, 30),
(2, 9, 20, 600.00, 0),
(2, 12, 40, 150.00, 0),
(3, 5, 60, 120.00, 30),
(3, 3, 40, 140.00, 0),
(4, 10, 50, 150.00, 50),
(5, 7, 25, 90.00, 0);

-- Advance the PO number counter past the seeded PO-0001..PO-0005 (next = PO-0006).
UPDATE purchase_order_sequence SET next_value = 6 WHERE id = 1;

-- ------------------------------------------------------------------ orders ---
-- 15 orders spanning the full lifecycle (OrderStatus): PENDING_ADMIN_APPROVAL,
-- APPROVED, LABEL_GENERATED, PACKED, COURIER_ASSIGNED, DISPATCHED,
-- OUT_FOR_DELIVERY, DELIVERED, COD_COLLECTED, RTO, COURIER_LOST, CANCELLED,
-- REJECTED. All source=SALESPERSON (storefront retired). Money math is
-- internally consistent: remaining_amount = total_amount - amount_received;
-- cod_amount = amount collectable on delivery; customer_outstanding = what the
-- customer still owes (0 once delivered/prepaid). created_by references a
-- seeded SALESPERSON (id 3 or 4). Payment statuses per PaymentStatus enum.
INSERT INTO orders (id, order_code, invoice_number, source, created_by, customer_name, customer_mobile, address_line, city, state, postal_code, total_amount, amount_received, remaining_amount, cod_amount, payment_status, order_status, customer_outstanding, rejection_reason, created_at, updated_at) VALUES
(1,  'SHR-1001', NULL,               'SALESPERSON', 3, 'Anita Sharma',  '9820011001', '14 MG Road',        'Indore',    'MP', '452001', 1347.00,    0.00, 1347.00, 1347.00, 'COD',           'PENDING_ADMIN_APPROVAL', 1347.00, NULL,                                              '2026-09-01 10:15:00', '2026-09-01 10:15:00'),
(2,  'SHR-1002', NULL,               'SALESPERSON', 4, 'Rahul Verma',   '9820011002', '7 Nehru Nagar',     'Bhopal',    'MP', '462001',  999.00,  999.00,    0.00,    0.00, 'FULLY_PAID',    'PENDING_ADMIN_APPROVAL',    0.00, NULL,                                              '2026-09-02 11:20:00', '2026-09-02 11:20:00'),
(3,  'SHR-1003', NULL,               'SALESPERSON', 3, 'Priya Nair',    '9820011003', '22 Lake View',      'Pune',      'MH', '411001',  837.00,  837.00,    0.00,    0.00, 'FULLY_PAID',    'APPROVED',                  0.00, NULL,                                              '2026-09-03 09:30:00', '2026-09-03 12:00:00'),
(4,  'SHR-1004', NULL,               'SALESPERSON', 4, 'Vikram Singh',  '9820011004', '5 Civil Lines',     'Jaipur',    'RJ', '302001',  558.00,    0.00,  558.00,  558.00, 'COD',           'LABEL_GENERATED',         558.00, NULL,                                              '2026-09-04 14:05:00', '2026-09-05 09:10:00'),
(5,  'SHR-1005', NULL,               'SALESPERSON', 3, 'Sunita Patel',  '9820011005', '88 Ring Road',      'Surat',     'GJ', '395001', 1548.00,  548.00, 1000.00, 1000.00, 'PARTIALLY_PAID','PACKED',                 1000.00, NULL,                                              '2026-09-05 16:40:00', '2026-09-07 10:00:00'),
(6,  'SHR-1006', NULL,               'SALESPERSON', 4, 'Amit Kulkarni', '9820011006', '12 FC Road',        'Pune',      'MH', '411004',  578.00,    0.00,  578.00,  578.00, 'COD',           'COURIER_ASSIGNED',        578.00, NULL,                                              '2026-09-06 12:10:00', '2026-09-08 11:30:00'),
(7,  'SHR-1007', NULL,               'SALESPERSON', 3, 'Deepa Iyer',    '9820011007', '3 Anna Salai',      'Chennai',   'TN', '600002',  798.00,  798.00,    0.00,    0.00, 'FULLY_PAID',    'DISPATCHED',                0.00, NULL,                                              '2026-09-07 15:25:00', '2026-09-09 09:45:00'),
(8,  'SHR-1008', NULL,               'SALESPERSON', 4, 'Manish Gupta',  '9820011008', '45 Park Street',    'Kolkata',   'WB', '700016',  747.00,    0.00,  747.00,  747.00, 'COD',           'OUT_FOR_DELIVERY',        747.00, NULL,                                              '2026-09-08 10:50:00', '2026-09-11 08:30:00'),
(9,  'SHR-1009', 'SHR/26-27/000001', 'SALESPERSON', 3, 'Kavya Reddy',   '9820011009', '9 Jubilee Hills',   'Hyderabad', 'TS', '500033',  947.00,    0.00,  947.00,  947.00, 'COD',           'DELIVERED',                 0.00, NULL,                                              '2026-09-09 13:15:00', '2026-09-13 17:20:00'),
(10, 'SHR-1010', 'SHR/26-27/000002', 'SALESPERSON', 4, 'Rohit Mehta',   '9820011010', '30 SG Highway',     'Ahmedabad', 'GJ', '380015',  949.00,  949.00,    0.00,    0.00, 'FULLY_PAID',    'DELIVERED',                 0.00, NULL,                                              '2026-09-10 09:05:00', '2026-09-14 16:10:00'),
(11, 'SHR-1011', 'SHR/26-27/000003', 'SALESPERSON', 3, 'Neha Joshi',    '9820011011', '16 Koramangala',    'Bengaluru', 'KA', '560034',  518.00,  518.00,    0.00,  518.00, 'COD',           'COD_COLLECTED',             0.00, NULL,                                              '2026-09-11 11:35:00', '2026-09-16 10:00:00'),
(12, 'SHR-1012', NULL,               'SALESPERSON', 4, 'Sanjay Rao',    '9820011012', '2 Banjara Hills',   'Hyderabad', 'TS', '500034',  418.00,    0.00,  418.00,  418.00, 'COD',           'RTO',                       0.00, NULL,                                              '2026-09-12 14:45:00', '2026-09-18 12:30:00'),
(13, 'SHR-1013', 'SHR/26-27/000004', 'SALESPERSON', 3, 'Farhan Khan',   '9820011013', '19 Marine Drive',   'Mumbai',    'MH', '400020',  999.00,  999.00,    0.00,    0.00, 'FULLY_PAID',    'COURIER_LOST',              0.00, NULL,                                              '2026-09-13 10:20:00', '2026-09-19 15:00:00'),
(14, 'SHR-1014', NULL,               'SALESPERSON', 4, 'Meera Desai',   '9820011014', '40 CG Road',        'Ahmedabad', 'GJ', '380009',  199.00,    0.00,  199.00,  199.00, 'COD',           'CANCELLED',                 0.00, 'Customer requested cancellation before approval', '2026-09-14 09:55:00', '2026-09-14 13:15:00'),
(15, 'SHR-1015', NULL,               'SALESPERSON', 3, 'Arjun Menon',   '9820011015', '6 Residency Road',  'Bengaluru', 'KA', '560025',  398.00,    0.00,  398.00,  398.00, 'COD',           'REJECTED',                  0.00, 'Suspected duplicate order; rejected by admin',    '2026-09-15 12:05:00', '2026-09-15 14:40:00');

-- Advance the invoice number counter past the four seeded invoices (next = 5).
UPDATE invoice_sequence SET next_value = 5 WHERE id = 1;

-- --------------------------------------------------------------- line_items ---
-- 1-3 lines per order; product_name/rate/line_total snapshotted at creation.
-- hsn_code / gst_rate are NULL (products carry no HSN/rate; matches source data).
INSERT INTO line_items (order_id, product_id, product_name, hsn_code, gst_rate, quantity, rate, line_total) VALUES
(1,  2,  'Ashwagandha Capsules (60 ct)',     NULL, NULL, 2, 549.00, 1098.00),
(1,  3,  'Triphala Churna (200 g)',          NULL, NULL, 1, 249.00,  249.00),
(2,  9,  'Shilajit Resin (20 g)',            NULL, NULL, 1, 999.00,  999.00),
(3,  6,  'Chyawanprash (500 g)',             NULL, NULL, 1, 399.00,  399.00),
(3,  5,  'Giloy Juice (500 ml)',             NULL, NULL, 2, 219.00,  438.00),
(4,  4,  'Brahmi Hair Oil (100 ml)',         NULL, NULL, 1, 299.00,  299.00),
(4,  10, 'Herbal Immunity Kadha (100 g)',    NULL, NULL, 1, 259.00,  259.00),
(5,  9,  'Shilajit Resin (20 g)',            NULL, NULL, 1, 999.00,  999.00),
(5,  2,  'Ashwagandha Capsules (60 ct)',     NULL, NULL, 1, 549.00,  549.00),
(6,  12, 'Moringa Powder (100 g)',           NULL, NULL, 2, 289.00,  578.00),
(7,  6,  'Chyawanprash (500 g)',             NULL, NULL, 2, 399.00,  798.00),
(8,  3,  'Triphala Churna (200 g)',          NULL, NULL, 3, 249.00,  747.00),
(9,  2,  'Ashwagandha Capsules (60 ct)',     NULL, NULL, 1, 549.00,  549.00),
(9,  7,  'Neem & Tulsi Face Wash (150 ml)',  NULL, NULL, 2, 199.00,  398.00),
(10, 1,  'Ashwagandha Herbal Capsules',      NULL, NULL, 1, 499.00,  499.00),
(10, 13, 'Karela Jamun Juice (500 ml)',      NULL, NULL, 2, 225.00,  450.00),
(11, 10, 'Herbal Immunity Kadha (100 g)',    NULL, NULL, 2, 259.00,  518.00),
(12, 5,  'Giloy Juice (500 ml)',             NULL, NULL, 1, 219.00,  219.00),
(12, 11, 'Aloe Vera Gel (200 ml)',           NULL, NULL, 1, 199.00,  199.00),
(13, 9,  'Shilajit Resin (20 g)',            NULL, NULL, 1, 999.00,  999.00),
(14, 7,  'Neem & Tulsi Face Wash (150 ml)',  NULL, NULL, 1, 199.00,  199.00),
(15, 8,  'Amla Juice (500 ml)',              NULL, NULL, 2, 199.00,  398.00);

-- ------------------------------------------------------------ status_history -
-- One row per lifecycle transition; the first row per order has from_status NULL
-- (creation into PENDING_ADMIN_APPROVAL). Every transition is legal per the
-- OrderStatus transition table, and the last to_status matches the order's
-- current order_status.
INSERT INTO status_history (order_id, from_status, to_status, actor, source, changed_at) VALUES
-- Order 1: PENDING_ADMIN_APPROVAL
(1, NULL, 'PENDING_ADMIN_APPROVAL', 'sales1', 'SALESPERSON', '2026-09-01 10:15:00'),
-- Order 2: PENDING_ADMIN_APPROVAL
(2, NULL, 'PENDING_ADMIN_APPROVAL', 'sales2', 'SALESPERSON', '2026-09-02 11:20:00'),
-- Order 3: APPROVED
(3, NULL, 'PENDING_ADMIN_APPROVAL', 'sales1', 'SALESPERSON', '2026-09-03 09:30:00'),
(3, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-03 12:00:00'),
-- Order 4: LABEL_GENERATED
(4, NULL, 'PENDING_ADMIN_APPROVAL', 'sales2', 'SALESPERSON', '2026-09-04 14:05:00'),
(4, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-04 17:00:00'),
(4, 'APPROVED', 'LABEL_GENERATED', 'admin', 'ADMIN', '2026-09-05 09:10:00'),
-- Order 5: PACKED
(5, NULL, 'PENDING_ADMIN_APPROVAL', 'sales1', 'SALESPERSON', '2026-09-05 16:40:00'),
(5, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-06 09:00:00'),
(5, 'APPROVED', 'LABEL_GENERATED', 'admin', 'ADMIN', '2026-09-06 11:00:00'),
(5, 'LABEL_GENERATED', 'PACKED', 'packer', 'PACKING_USER', '2026-09-07 10:00:00'),
-- Order 6: COURIER_ASSIGNED
(6, NULL, 'PENDING_ADMIN_APPROVAL', 'sales2', 'SALESPERSON', '2026-09-06 12:10:00'),
(6, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-07 09:00:00'),
(6, 'APPROVED', 'LABEL_GENERATED', 'admin', 'ADMIN', '2026-09-07 11:00:00'),
(6, 'LABEL_GENERATED', 'PACKED', 'packer', 'PACKING_USER', '2026-09-08 09:00:00'),
(6, 'PACKED', 'COURIER_ASSIGNED', 'system', 'SYSTEM', '2026-09-08 11:30:00'),
-- Order 7: DISPATCHED
(7, NULL, 'PENDING_ADMIN_APPROVAL', 'sales1', 'SALESPERSON', '2026-09-07 15:25:00'),
(7, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-08 09:00:00'),
(7, 'APPROVED', 'LABEL_GENERATED', 'admin', 'ADMIN', '2026-09-08 11:00:00'),
(7, 'LABEL_GENERATED', 'PACKED', 'packer', 'PACKING_USER', '2026-09-08 15:00:00'),
(7, 'PACKED', 'COURIER_ASSIGNED', 'system', 'SYSTEM', '2026-09-09 08:00:00'),
(7, 'COURIER_ASSIGNED', 'DISPATCHED', 'system', 'COURIER', '2026-09-09 09:45:00'),
-- Order 8: OUT_FOR_DELIVERY
(8, NULL, 'PENDING_ADMIN_APPROVAL', 'sales2', 'SALESPERSON', '2026-09-08 10:50:00'),
(8, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-09 09:00:00'),
(8, 'APPROVED', 'LABEL_GENERATED', 'admin', 'ADMIN', '2026-09-09 11:00:00'),
(8, 'LABEL_GENERATED', 'PACKED', 'packer', 'PACKING_USER', '2026-09-09 15:00:00'),
(8, 'PACKED', 'COURIER_ASSIGNED', 'system', 'SYSTEM', '2026-09-10 08:00:00'),
(8, 'COURIER_ASSIGNED', 'DISPATCHED', 'system', 'COURIER', '2026-09-10 10:00:00'),
(8, 'DISPATCHED', 'IN_TRANSIT', 'system', 'COURIER', '2026-09-10 18:00:00'),
(8, 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'system', 'COURIER', '2026-09-11 08:30:00'),
-- Order 9: DELIVERED (COD)
(9, NULL, 'PENDING_ADMIN_APPROVAL', 'sales1', 'SALESPERSON', '2026-09-09 13:15:00'),
(9, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-10 09:00:00'),
(9, 'APPROVED', 'LABEL_GENERATED', 'admin', 'ADMIN', '2026-09-10 11:00:00'),
(9, 'LABEL_GENERATED', 'PACKED', 'packer', 'PACKING_USER', '2026-09-10 15:00:00'),
(9, 'PACKED', 'COURIER_ASSIGNED', 'system', 'SYSTEM', '2026-09-11 08:00:00'),
(9, 'COURIER_ASSIGNED', 'DISPATCHED', 'system', 'COURIER', '2026-09-11 10:00:00'),
(9, 'DISPATCHED', 'IN_TRANSIT', 'system', 'COURIER', '2026-09-12 09:00:00'),
(9, 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'system', 'COURIER', '2026-09-13 08:00:00'),
(9, 'OUT_FOR_DELIVERY', 'DELIVERED', 'system', 'COURIER', '2026-09-13 17:20:00'),
-- Order 10: DELIVERED (prepaid)
(10, NULL, 'PENDING_ADMIN_APPROVAL', 'sales2', 'SALESPERSON', '2026-09-10 09:05:00'),
(10, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-11 09:00:00'),
(10, 'APPROVED', 'LABEL_GENERATED', 'admin', 'ADMIN', '2026-09-11 11:00:00'),
(10, 'LABEL_GENERATED', 'PACKED', 'packer', 'PACKING_USER', '2026-09-11 15:00:00'),
(10, 'PACKED', 'COURIER_ASSIGNED', 'system', 'SYSTEM', '2026-09-12 08:00:00'),
(10, 'COURIER_ASSIGNED', 'DISPATCHED', 'system', 'COURIER', '2026-09-12 10:00:00'),
(10, 'DISPATCHED', 'IN_TRANSIT', 'system', 'COURIER', '2026-09-13 09:00:00'),
(10, 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'system', 'COURIER', '2026-09-14 08:00:00'),
(10, 'OUT_FOR_DELIVERY', 'DELIVERED', 'system', 'COURIER', '2026-09-14 16:10:00'),
-- Order 11: COD_COLLECTED
(11, NULL, 'PENDING_ADMIN_APPROVAL', 'sales1', 'SALESPERSON', '2026-09-11 11:35:00'),
(11, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-12 09:00:00'),
(11, 'APPROVED', 'LABEL_GENERATED', 'admin', 'ADMIN', '2026-09-12 11:00:00'),
(11, 'LABEL_GENERATED', 'PACKED', 'packer', 'PACKING_USER', '2026-09-12 15:00:00'),
(11, 'PACKED', 'COURIER_ASSIGNED', 'system', 'SYSTEM', '2026-09-13 08:00:00'),
(11, 'COURIER_ASSIGNED', 'DISPATCHED', 'system', 'COURIER', '2026-09-13 10:00:00'),
(11, 'DISPATCHED', 'IN_TRANSIT', 'system', 'COURIER', '2026-09-14 09:00:00'),
(11, 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'system', 'COURIER', '2026-09-15 08:00:00'),
(11, 'OUT_FOR_DELIVERY', 'DELIVERED', 'system', 'COURIER', '2026-09-15 16:00:00'),
(11, 'DELIVERED', 'COD_COLLECTED', 'accountant', 'ACCOUNTANT', '2026-09-16 10:00:00'),
-- Order 12: RTO
(12, NULL, 'PENDING_ADMIN_APPROVAL', 'sales2', 'SALESPERSON', '2026-09-12 14:45:00'),
(12, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-13 09:00:00'),
(12, 'APPROVED', 'LABEL_GENERATED', 'admin', 'ADMIN', '2026-09-13 11:00:00'),
(12, 'LABEL_GENERATED', 'PACKED', 'packer', 'PACKING_USER', '2026-09-13 15:00:00'),
(12, 'PACKED', 'COURIER_ASSIGNED', 'system', 'SYSTEM', '2026-09-14 08:00:00'),
(12, 'COURIER_ASSIGNED', 'DISPATCHED', 'system', 'COURIER', '2026-09-14 10:00:00'),
(12, 'DISPATCHED', 'IN_TRANSIT', 'system', 'COURIER', '2026-09-15 09:00:00'),
(12, 'IN_TRANSIT', 'RTO', 'system', 'COURIER', '2026-09-18 12:30:00'),
-- Order 13: COURIER_LOST
(13, NULL, 'PENDING_ADMIN_APPROVAL', 'sales1', 'SALESPERSON', '2026-09-13 10:20:00'),
(13, 'PENDING_ADMIN_APPROVAL', 'APPROVED', 'admin', 'ADMIN', '2026-09-14 09:00:00'),
(13, 'APPROVED', 'LABEL_GENERATED', 'admin', 'ADMIN', '2026-09-14 11:00:00'),
(13, 'LABEL_GENERATED', 'PACKED', 'packer', 'PACKING_USER', '2026-09-14 15:00:00'),
(13, 'PACKED', 'COURIER_ASSIGNED', 'system', 'SYSTEM', '2026-09-15 08:00:00'),
(13, 'COURIER_ASSIGNED', 'DISPATCHED', 'system', 'COURIER', '2026-09-15 10:00:00'),
(13, 'DISPATCHED', 'IN_TRANSIT', 'system', 'COURIER', '2026-09-16 09:00:00'),
(13, 'IN_TRANSIT', 'COURIER_LOST', 'system', 'COURIER', '2026-09-19 15:00:00'),
-- Order 14: CANCELLED
(14, NULL, 'PENDING_ADMIN_APPROVAL', 'sales2', 'SALESPERSON', '2026-09-14 09:55:00'),
(14, 'PENDING_ADMIN_APPROVAL', 'CANCELLED', 'admin', 'ADMIN', '2026-09-14 13:15:00'),
-- Order 15: REJECTED
(15, NULL, 'PENDING_ADMIN_APPROVAL', 'sales1', 'SALESPERSON', '2026-09-15 12:05:00'),
(15, 'PENDING_ADMIN_APPROVAL', 'REJECTED', 'admin', 'ADMIN', '2026-09-15 14:40:00');

-- --------------------------------------------------------------- payments -----
-- One row per money-received event: prepaid (FULLY_PAID) captures, the partial
-- payment on order 5, and the COD cash collected on order 11.
INSERT INTO payments (order_id, amount_received, captured_at) VALUES
(2,  999.00, '2026-09-02 11:20:00'),
(3,  837.00, '2026-09-03 09:30:00'),
(5,  548.00, '2026-09-05 16:40:00'),
(7,  798.00, '2026-09-07 15:25:00'),
(10, 949.00, '2026-09-10 09:05:00'),
(13, 999.00, '2026-09-13 10:20:00'),
(11, 518.00, '2026-09-16 10:00:00');

-- ---------------------------------------------------------- courier_records --
-- One record per order that reached courier assignment onward (all via the
-- seeded "Shifa Express" company, id 1). One record per order (unique order_id).
INSERT INTO courier_records (order_id, courier_company_id, awb, estimated_delivery, last_courier_status) VALUES
(6,  1, 'SHFEXP1000006', '2026-09-12', 'ASSIGNED'),
(7,  1, 'SHFEXP1000007', '2026-09-12', 'DISPATCHED'),
(8,  1, 'SHFEXP1000008', '2026-09-12', 'OUT_FOR_DELIVERY'),
(9,  1, 'SHFEXP1000009', '2026-09-13', 'DELIVERED'),
(10, 1, 'SHFEXP1000010', '2026-09-14', 'DELIVERED'),
(11, 1, 'SHFEXP1000011', '2026-09-15', 'DELIVERED'),
(12, 1, 'SHFEXP1000012', '2026-09-16', 'RTO'),
(13, 1, 'SHFEXP1000013', '2026-09-17', 'LOST');

-- ------------------------------------------------------------- receivables ---
-- COD cash the courier owes back on delivered COD orders, plus a claim for the
-- lost shipment. Types per ReceivableType (COD_RECEIVABLE / CLAIM_RECEIVABLE).
INSERT INTO receivables (order_id, courier_company_id, type, amount, settled, settled_date, created_at) VALUES
(9,  1, 'COD_RECEIVABLE',   947.00, 0, NULL,          '2026-09-13 17:20:00'),
(11, 1, 'COD_RECEIVABLE',   518.00, 1, '2026-09-16', '2026-09-15 16:00:00'),
(13, 1, 'CLAIM_RECEIVABLE', 999.00, 0, NULL,          '2026-09-19 15:00:00');

-- ---------------------------------------------------------- stock_movements --
-- Ledger of RESTOCK (from received POs), SALE (fulfilled orders), RETURN and
-- ADJUSTMENT. movement_type per V11 CHECK (RESTOCK/ADJUSTMENT/SALE/RETURN).
-- balance_after is a plausible snapshot of on-hand quantity after the movement.
INSERT INTO stock_movements (product_id, delta, movement_type, reason, balance_after, created_by, created_at) VALUES
(2,   50, 'RESTOCK',    'PO-0001 received',              89, 1, '2026-07-27 14:30:00'),
(6,   30, 'RESTOCK',    'PO-0001 received',              47, 1, '2026-07-27 14:30:00'),
(10,  50, 'RESTOCK',    'PO-0004 received',              80, 1, '2026-08-22 12:00:00'),
(5,   30, 'RESTOCK',    'PO-0003 partial receipt',       90, 1, '2026-08-14 10:00:00'),
(2,   -2, 'SALE',       'Order SHR-1009 fulfilled',      39, 5, '2026-09-13 17:20:00'),
(7,   -2, 'SALE',       'Order SHR-1009 fulfilled',       0, 5, '2026-09-13 17:20:00'),
(10,  -2, 'SALE',       'Order SHR-1011 fulfilled',      30, 5, '2026-09-15 16:00:00'),
(6,   -2, 'SALE',       'Order SHR-1007 fulfilled',      17, 5, '2026-09-09 09:45:00'),
(4,    1, 'ADJUSTMENT', 'Stock recount correction',       2, 1, '2026-09-01 09:00:00'),
(7,   -1, 'ADJUSTMENT', 'Damaged in storage - written off', 0, 1, '2026-09-02 09:00:00'),
(5,    1, 'RETURN',     'Customer return restocked',     60, 1, '2026-09-18 12:30:00');

-- ------------------------------------------------------------ order_returns ---
-- Returns against delivered / RTO orders across the ReturnStatus lifecycle
-- (REQUESTED / APPROVED / REFUNDED).
INSERT INTO order_returns (order_id, reason, notes, status, refund_amount, restocked, created_by, created_at, updated_at) VALUES
(9,  'Damaged product on arrival',    'Customer reported a broken seal; awaiting review.', 'REQUESTED', NULL,   0, 3, '2026-09-14 10:00:00', NULL),
(10, 'Customer changed mind',         'Refund issued to original payment method.',         'REFUNDED',  949.00, 1, 1, '2026-09-15 09:00:00', '2026-09-17 11:00:00'),
(12, 'RTO - address not found',       'Auto-created from RTO; stock returned to inventory.','APPROVED',  NULL,   1, 1, '2026-09-18 13:00:00', '2026-09-18 14:00:00');

-- ---------------------------------------------------------------- expenses ---
-- Manually recorded business expenses feeding the P&L (category is free-text).
INSERT INTO expenses (category, description, amount, incurred_on, created_by, created_at) VALUES
('RENT',          'Warehouse monthly rent',            25000.00, '2026-08-01', 2, '2026-08-01 10:00:00'),
('SALARIES',      'Staff salaries - August',           85000.00, '2026-08-31', 2, '2026-08-31 18:00:00'),
('MARKETING',     'Instagram ads campaign',            12000.00, '2026-08-15', 2, '2026-08-15 11:00:00'),
('PACKAGING',     'Corrugated boxes & packing tape',    8500.00, '2026-08-10', 2, '2026-08-10 12:00:00'),
('UTILITIES',     'Electricity & water',                6200.00, '2026-08-05', 2, '2026-08-05 09:30:00'),
('SHIPPING',      'Courier monthly settlement fees',   15400.00, '2026-08-28', 2, '2026-08-28 17:00:00'),
('RENT',          'Warehouse monthly rent',            25000.00, '2026-09-01', 2, '2026-09-01 10:00:00'),
('SALARIES',      'Staff salaries - September',        88000.00, '2026-09-30', 2, '2026-09-30 18:00:00'),
('MARKETING',     'Google Ads - search',                9800.00, '2026-09-12', 2, '2026-09-12 11:00:00'),
('MISCELLANEOUS', 'Office supplies & sundry expenses',  3400.00, '2026-09-18', 2, '2026-09-18 15:00:00');

-- ----------------------------------------------------- admin_notifications ---
-- Durable admin alerts with read/unread state; source_event_id is NULL
-- (manually recorded, not tied to an outbox event).
INSERT INTO admin_notifications (type, title, detail, severity, order_id, order_code, source_event_id, read_flag, created_at, read_at) VALUES
('ORDER_PACKED',          'Order SHR-1005 packed',           'Order SHR-1005 is packed and ready for courier assignment.', 'info',    5,    'SHR-1005', NULL, 1, '2026-09-07 10:00:00', '2026-09-07 10:30:00'),
('LOW_STOCK',             'Low stock: Brahmi Hair Oil',      'Brahmi Hair Oil (100 ml) is down to 2 units (threshold 5).',  'warning', NULL, NULL,       NULL, 0, '2026-09-08 08:00:00', NULL),
('ORDER_STATUS_CHANGED',  'Order SHR-1009 delivered',        'Order SHR-1009 was marked DELIVERED by the courier.',        'info',    9,    'SHR-1009', NULL, 0, '2026-09-13 17:20:00', NULL),
('CLAIM_FILED_REQUIRED',  'Courier lost shipment SHR-1013',  'File a claim with Shifa Express for lost order SHR-1013.',    'error',   13,   'SHR-1013', NULL, 0, '2026-09-19 15:00:00', NULL),
('COURIER_ASSIGN_FAILED', 'Courier assignment retried',      'Temporary failure assigning courier for SHR-1006; retried.', 'warning', 6,    'SHR-1006', NULL, 1, '2026-09-08 11:00:00', '2026-09-08 11:45:00');

-- ---------------------------------------------------------- audit_events -----
-- Global "who did what, when" trail across key mutating actions.
INSERT INTO audit_events (actor_user_id, actor_username, action, entity_type, entity_id, summary, created_at) VALUES
(1, 'admin',      'ORDER_APPROVED',   'ORDER',    '3',  'Order SHR-1003 approved by admin',                     '2026-09-03 12:00:00'),
(1, 'admin',      'ORDER_REJECTED',   'ORDER',    '15', 'Order SHR-1015 rejected: suspected duplicate order',   '2026-09-15 14:40:00'),
(2, 'accountant', 'EXPENSE_ADDED',    'EXPENSE',  '1',  'Expense added: RENT 25000.00 on 2026-08-01',           '2026-08-01 10:00:00'),
(1, 'admin',      'SETTINGS_UPDATED', 'SETTINGS', '1',  'Company GST + invoice settings updated',               '2026-07-04 16:33:50'),
(1, 'admin',      'STOCK_RESTOCKED',  'PRODUCT',  '2',  'Restocked Ashwagandha Capsules (60 ct) +50 via PO-0001', '2026-07-27 14:30:00');

-- ---------------------------------------------------------- app_settings -----
-- Enrich the single settings row (already inserted with id=1 by V2) with the
-- demo company's GST + invoice configuration so invoices/labels render fully.
UPDATE app_settings SET
    gst_enabled           = 1,
    gstin                 = '23ABCDE1234F1Z5',
    legal_name            = 'Shifa Herbal Remedies',
    address_line          = '12 Herbal Lane',
    city                  = 'Indore',
    state                 = 'MP',
    state_code            = '23',
    gst_rate_percent      = 5.00,
    prices_include_gst    = 1,
    invoice_footer_note   = 'Thank you for shopping with Shifa.',
    invoice_number_prefix = 'SHR/26-27/'
WHERE id = 1;
