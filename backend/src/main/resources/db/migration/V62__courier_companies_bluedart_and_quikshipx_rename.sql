-- Delivery-partner dropdown + courier-name display fix.
--
-- 1) Seed "Blue Dart" as a selectable delivery partner (TrackDart third-party
--    tracking URL; {awb} substituted at runtime by CourierCompany.trackingUrl).
--    Idempotent: skipped when a Blue Dart row already exists under either
--    spelling (some environments — e.g. the V27 test-data seed — already have
--    a "BlueDart" row without the space).
INSERT INTO courier_companies (name, tracking_url_template)
SELECT 'Blue Dart', 'https://www.bluedart.com/web/guest/trackdartresultthirdparty?trackFor=0&trackNo={awb}'
WHERE NOT EXISTS (SELECT 1 FROM courier_companies WHERE name IN ('Blue Dart', 'BlueDart'));

-- 2) Fix "COURIER: DIRECT_DELIVERY" showing on labels/order-detail: earlier
--    QuikShipX shipments upserted a courier_companies row named after the raw
--    sub-courier QuikShipX allots under the hood (e.g. "Direct_Delhivery"), an
--    internal QuikShipX routing detail meaningless to our own team. The
--    partner actually selected is QuikShipX itself, so rename any such
--    existing row to "QuikShipX" (only when no "QuikShipX" row already exists,
--    to avoid a duplicate-name clash — harmless either way since courier
--    records key by id, not name).
-- MySQL disallows selecting from the same table being updated directly in a
-- subquery ("You can't specify target table ... for update in FROM clause");
-- wrapping the check in a derived table (materialized separately) avoids it.
UPDATE courier_companies
   SET name = 'QuikShipX'
 WHERE name = 'Direct_Delhivery'
   AND NOT EXISTS (SELECT 1 FROM (SELECT name FROM courier_companies) AS existing WHERE existing.name = 'QuikShipX');
