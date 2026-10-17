-- CRM expansion Phase 1 follow-up (crm-expansion spec, Req 7).
--
-- Adds optional address fields to the leads aggregate so a captured lead can
-- carry the customer's delivery address. This lets the capture form offer
-- pincode -> city/state auto-fill, and lets lead -> order conversion pre-fill
-- the address instead of making the salesperson re-type it.
--
-- All columns are nullable: existing leads are unaffected and keep working
-- unchanged (address is empty until captured/edited).

ALTER TABLE leads ADD COLUMN address_line VARCHAR(250) NULL,  -- free-text shipping address line
                  ADD COLUMN city         VARCHAR(100) NULL,
                  ADD COLUMN state        VARCHAR(100) NULL,
                  ADD COLUMN postal_code  VARCHAR(10)  NULL;   -- 6-digit Indian PIN (validated in the API)
