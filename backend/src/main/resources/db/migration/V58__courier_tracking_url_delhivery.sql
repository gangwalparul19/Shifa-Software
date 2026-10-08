-- V58: point courier tracking links at Delhivery's real public tracking page
-- instead of the demo placeholder (track.example.com).
--
-- QuikShipX parcels ship via Delhivery and the AWB is the Delhivery waybill, so
-- the customer-facing "track your order" links in the dispatch WhatsApp/email
-- (built from courier_companies.tracking_url_template) must resolve to a working
-- page. The seeded/demo companies were created with a placeholder template
-- (https://track.example.com/{awb}); rewrite those to Delhivery's public page.
--
-- Idempotent: only the known placeholder rows are rewritten; real templates are
-- left untouched. The {awb} token is substituted at runtime by CourierCompany.
UPDATE courier_companies
   SET tracking_url_template = 'https://www.delhivery.com/track/package/{awb}'
 WHERE tracking_url_template LIKE '%track.example.com%';
