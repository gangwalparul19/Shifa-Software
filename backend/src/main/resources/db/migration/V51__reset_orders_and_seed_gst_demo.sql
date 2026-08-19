-- =============================================================================
-- V51 — Reset ALL orders and seed a GST demo dataset (~200 orders) spanning
-- Aug 2025 → Aug 2026, so the CA GST dashboard/report has consolidated data
-- across FY2025-26 and FY2026-27.
--
-- ⚠️ DESTRUCTIVE: deletes every existing order and its children (line items,
-- payments, status history, receivables, courier records, returns). Runs in ALL
-- environments (local + server) — this is intentional per the requirement to
-- start fresh with GST-ready data. Additive product catalog (V50) is untouched.
-- =============================================================================

-- --- 1. Set the seller GST identity so CGST/SGST (intra) vs IGST (inter) work --
-- Home state Madhya Pradesh (Indore), state code 23. GSTIN is a valid-format
-- placeholder; adjust in Settings if the real one differs.
UPDATE app_settings
SET gst_enabled = 1,
    gstin = COALESCE(NULLIF(gstin, ''), '23AABCS1234F1Z5'),
    state = 'Madhya Pradesh',
    state_code = '23'
WHERE id = 1;

-- --- 2. Clear all order data (children first; unlink leads) -------------------
UPDATE leads SET converted_order_id = NULL WHERE converted_order_id IS NOT NULL;
DELETE FROM line_items;
DELETE FROM payments;
DELETE FROM status_history;
DELETE FROM receivables;
DELETE FROM courier_records;
DELETE FROM order_returns;
DELETE FROM orders;

-- --- 3. Seed 200 order shells (amounts filled in step 5) ----------------------
-- Numbers 1..200 via a digit cross-join (no stored proc / recursion, so Flyway
-- runs it as a single statement). n drives date, customer, state, and pattern.
INSERT INTO orders (
    order_code, source, lead_source, created_by,
    customer_name, customer_mobile, address_line, city, state, postal_code,
    total_amount, amount_received, remaining_amount, cod_amount,
    payment_status, order_status, customer_outstanding, discount_amount,
    version, package_count, created_at, updated_at)
SELECT
    CONCAT('SHR-GST-', LPAD(n, 5, '0')),
    'SALESPERSON',
    ELT(1 + (n MOD 5), 'WHATSAPP', 'INSTAGRAM', 'FACEBOOK', 'GOOGLE', 'OFFLINE'),
    COALESCE((SELECT id FROM users WHERE username = 'sales1' LIMIT 1),
             (SELECT MIN(id) FROM users)),
    CONCAT('Customer ', LPAD(1 + (n MOD 60), 2, '0')),
    LPAD(9000000000 + (n MOD 60), 10, '0'),
    CONCAT(1 + (n MOD 90), ' Market Road'),
    ELT(1 + ((n MOD 60) MOD 7), 'Indore', 'Mumbai', 'Ahmedabad', 'Jaipur', 'Lucknow', 'New Delhi', 'Bengaluru'),
    ELT(1 + ((n MOD 60) MOD 7), 'Madhya Pradesh', 'Maharashtra', 'Gujarat', 'Rajasthan', 'Uttar Pradesh', 'Delhi', 'Karnataka'),
    ELT(1 + ((n MOD 60) MOD 7), '452001', '400001', '380001', '302001', '226001', '110001', '560001'),
    0, 0, 0, 0,
    'FULLY_PAID', 'DELIVERED', 0, 0.00,
    0, 1,
    TIMESTAMP('2025-08-01 10:00:00') + INTERVAL FLOOR((n - 1) * 383 / 200) DAY + INTERVAL (n MOD 9) HOUR,
    TIMESTAMP('2025-08-01 10:00:00') + INTERVAL FLOOR((n - 1) * 383 / 200) DAY + INTERVAL (n MOD 9) HOUR
FROM (
    SELECT c.i * 100 + b.i * 10 + a.i AS n
    FROM (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
    CROSS JOIN (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
    CROSS JOIN (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2) c
) nums
WHERE n BETWEEN 1 AND 200;

-- --- 4. Seed line items (1-2 per order) from the SHIFA catalog ----------------
-- Prices are the product's auto-fetch (sale) price — GST-inclusive; hsn_code +
-- gst_rate are snapshotted from the product, exactly as real orders do.
-- Line A (every order):
INSERT INTO line_items (order_id, product_id, product_name, hsn_code, gst_rate, quantity, rate, line_total)
SELECT o.id, p.id, p.name, p.hsn_code, p.gst_rate,
       1 + (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 3),
       p.sale_price,
       p.sale_price * (1 + (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 3))
FROM orders o
JOIN products p ON p.sku = CONCAT('SHIFA-', LPAD(1 + (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 30), 3, '0'))
WHERE o.order_code LIKE 'SHR-GST-%';

-- Line B (roughly 2/3 of orders — adds rate/HSN variety):
INSERT INTO line_items (order_id, product_id, product_name, hsn_code, gst_rate, quantity, rate, line_total)
SELECT o.id, p.id, p.name, p.hsn_code, p.gst_rate,
       1 + (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 2),
       p.sale_price,
       p.sale_price * (1 + (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 2))
FROM orders o
JOIN products p ON p.sku = CONCAT('SHIFA-', LPAD(1 + ((CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) * 7 + 3) MOD 30), 3, '0'))
WHERE o.order_code LIKE 'SHR-GST-%'
  AND (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 3) <> 0;

-- --- 5. Fill order totals + a realistic payment/status mix (by n MOD 10) ------
-- Buckets: 0-4 prepaid FULLY_PAID (delivered/closed); 5-7 COD collected;
-- 8 COD pending (out for delivery); 9 partial prepaid. All are revenue orders
-- (none cancelled/rejected) so they all count toward GST.
UPDATE orders o
JOIN (SELECT order_id, SUM(line_total) AS tot FROM line_items GROUP BY order_id) s ON s.order_id = o.id
SET
    o.total_amount = s.tot,
    o.order_status = CASE (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 10)
        WHEN 0 THEN 'CLOSED' WHEN 1 THEN 'CLOSED'
        WHEN 2 THEN 'DELIVERED' WHEN 3 THEN 'DELIVERED' WHEN 4 THEN 'DELIVERED'
        WHEN 5 THEN 'COD_COLLECTED' WHEN 6 THEN 'COD_COLLECTED' WHEN 7 THEN 'COD_COLLECTED'
        WHEN 8 THEN 'OUT_FOR_DELIVERY' ELSE 'APPROVED' END,
    o.payment_status = CASE (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 10)
        WHEN 5 THEN 'COD' WHEN 6 THEN 'COD' WHEN 7 THEN 'COD' WHEN 8 THEN 'COD'
        WHEN 9 THEN 'PARTIALLY_PAID' ELSE 'FULLY_PAID' END,
    o.amount_received = CASE (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 10)
        WHEN 8 THEN 0.00
        WHEN 9 THEN ROUND(s.tot * 0.4, 2)
        ELSE s.tot END,
    o.cod_amount = CASE (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 10)
        WHEN 5 THEN s.tot WHEN 6 THEN s.tot WHEN 7 THEN s.tot WHEN 8 THEN s.tot
        ELSE 0.00 END,
    o.remaining_amount = CASE (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 10)
        WHEN 8 THEN s.tot
        WHEN 9 THEN s.tot - ROUND(s.tot * 0.4, 2)
        ELSE 0.00 END,
    o.customer_outstanding = CASE (CAST(SUBSTRING(o.order_code, 9) AS UNSIGNED) MOD 10)
        WHEN 8 THEN s.tot
        WHEN 9 THEN s.tot - ROUND(s.tot * 0.4, 2)
        ELSE 0.00 END
WHERE o.order_code LIKE 'SHR-GST-%';

-- --- 6. One creation status-history row + a payment row where money came in ---
INSERT INTO status_history (order_id, from_status, to_status, actor, source, changed_at)
SELECT id, NULL, order_status, 'system', 'SEED', created_at
FROM orders WHERE order_code LIKE 'SHR-GST-%';

INSERT INTO payments (order_id, amount_received, captured_at)
SELECT id, amount_received, created_at
FROM orders WHERE order_code LIKE 'SHR-GST-%' AND amount_received > 0;
