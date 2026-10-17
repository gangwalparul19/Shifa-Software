-- CRM expansion Phase 2, Task 12 (design §2.4): mark a due, reminded follow-up
-- that received no action within the grace window as Missed (Req 10). Additive,
-- nullable — existing leads are unaffected (null = not missed).
ALTER TABLE leads ADD COLUMN follow_up_missed_at DATETIME NULL;
CREATE INDEX ix_leads_follow_up_missed ON leads (follow_up_missed_at);
