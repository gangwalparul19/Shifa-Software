-- =============================================================================
-- V30 — Remove the demo/test in-app notifications planted by the seed data.
--
-- The V22 (demo) and V27 (test) seeds inserted a fixed set of admin_notifications
-- rows with NOW()-relative timestamps, so they perpetually surface as "just now"
-- in the notification bell and read as hard-coded/non-live clutter. They are pure
-- sample data, not real activity.
--
-- This migration deletes ONLY those seeded rows, matched by their exact seeded
-- `detail` text (each string is unique to the seed) and guarded to rows with no
-- originating outbox event (`source_event_id IS NULL`, as all seeded rows are).
-- Genuine, app-generated notifications — including inline workflow ones and any
-- future events — reference real orders/values with different text and are left
-- untouched. Additive and safe; runs once after the seeds.
-- =============================================================================

DELETE FROM admin_notifications
WHERE source_event_id IS NULL
  AND detail IN (
    -- V22 demo seed (5 rows)
    'Order SHR-1005 is packed and ready for courier assignment.',
    'Brahmi Hair Oil (100 ml) is down to 2 units (threshold 5).',
    'Order SHR-1009 was marked DELIVERED by the courier.',
    'File a claim with Shifa Express for lost order SHR-1013.',
    'Temporary failure assigning courier for SHR-1006; retried.',
    -- V27 test seed (13 rows)
    'Order SHR-5017 was marked DELIVERED by the courier.',
    'Brahmi Hair Oil (100 ml) has fallen below its reorder threshold.',
    'Aloe Vera Gel (200 ml) is running low - consider a restock PO.',
    'Temporary failure assigning courier for SHR-5073; auto-retried.',
    'File a claim for lost order SHR-5117.',
    'COD receivables pending settlement have crossed the alert level.',
    'Order SHR-5110 shows elevated RTO risk.',
    'You have leads with a follow-up scheduled for today.',
    'A lead follow-up is overdue - please reach out.',
    'A new salesperson order is pending admin approval.',
    'One of your orders has been approved and is being processed.',
    'Neem & Tulsi Face Wash (150 ml) is out of stock.',
    'Delhivery delivery success rate dropped this week.'
  );
