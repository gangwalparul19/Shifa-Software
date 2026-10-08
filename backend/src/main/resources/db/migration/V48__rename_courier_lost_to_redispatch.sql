-- =============================================================================
-- Rename the former COURIER_LOST terminal outcome to REDISPATCH.
--
-- V22/V27 remain immutable historical seeds and still contain the prior value.
-- These data updates therefore make both upgraded and fresh databases end at the
-- new status, while retaining the existing claim-receivable financial workflow.
-- =============================================================================

UPDATE orders
SET order_status = 'REDISPATCH'
WHERE order_status = 'COURIER_LOST';

UPDATE status_history
SET from_status = 'REDISPATCH'
WHERE from_status = 'COURIER_LOST';

UPDATE status_history
SET to_status = 'REDISPATCH'
WHERE to_status = 'COURIER_LOST';

-- These fields are known user-facing notification text fields (V16/V24).
UPDATE admin_notifications
SET title = REPLACE(REPLACE(REPLACE(title, 'Courier Lost', 'Redispatch'),
                                    'Courier lost', 'Redispatch'),
                    'COURIER_LOST', 'REDISPATCH'),
    detail = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(detail, 'Courier Lost', 'Redispatch'),
                                            'Courier lost', 'Redispatch'),
                                    'COURIER_LOST', 'REDISPATCH'),
                            'lost shipment', 'redispatch shipment'),
                     'lost order', 'redispatch order')
WHERE title LIKE '%Courier Lost%'
   OR title LIKE '%Courier lost%'
   OR title LIKE '%COURIER_LOST%'
   OR detail LIKE '%Courier Lost%'
   OR detail LIKE '%Courier lost%'
   OR detail LIKE '%COURIER_LOST%'
   OR detail LIKE '%lost shipment%'
   OR detail LIKE '%lost order%';

-- Outbox payload is a known JSON column (V1). Keep historic status/template
-- payloads compatible with the renamed API and customer notification template.
UPDATE outbox
SET payload = CAST(
        REPLACE(
                REPLACE(CAST(payload AS CHAR CHARACTER SET utf8mb4),
                        'order_courier_lost', 'order_redispatch'),
                'COURIER_LOST', 'REDISPATCH')
        AS JSON)
WHERE payload IS NOT NULL
  AND (CAST(payload AS CHAR CHARACTER SET utf8mb4) LIKE '%COURIER_LOST%'
       OR CAST(payload AS CHAR CHARACTER SET utf8mb4) LIKE '%order_courier_lost%');
