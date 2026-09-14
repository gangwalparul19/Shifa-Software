-- V59: we do NOT track with any individual carrier (e.g. Delhivery) directly.
-- QuikShipX is the courier aggregator/partner and tracking is done through their
-- track-order API (surfaced in-app). V58 had pointed the template at Delhivery's
-- public page, but that carrier is not our partner — clear the carrier-specific
-- tracking URL template so no carrier link is built for a shipment. Tracking is
-- via QuikShipX (order-id based) instead.
--
-- Idempotent: only rewrites the carrier URLs introduced in V58.
UPDATE courier_companies
   SET tracking_url_template = NULL
 WHERE tracking_url_template LIKE '%delhivery.com%';
