-- CRM expansion Phase 2, Task 9 (design §2.1): the unified Activity log.
-- An append-only feed that backs the per-lead and per-customer activity
-- timelines (Req 11) and the "was the follow-up acted on?" check (Req 10).
-- Additive; nothing else depends on it until the activity package lands.
CREATE TABLE crm_activities (
  id BIGINT NOT NULL AUTO_INCREMENT,
  activity_type VARCHAR(40) NOT NULL,     -- STAGE_CHANGE, TAG_ADDED, TAG_REMOVED, NOTE, ASSIGNED,
                                          -- FOLLOW_UP_SET, FOLLOW_UP_MISSED, TASK_CREATED, TASK_DONE,
                                          -- MEETING_SCHEDULED, MEETING_OUTCOME, CALL, WHATSAPP, ...
  lead_id BIGINT NULL,
  customer_mobile VARCHAR(10) NULL,       -- for customer-timeline aggregation (Customer 360 keying)
  actor_user_id BIGINT NULL,
  actor_name VARCHAR(150) NULL,
  summary VARCHAR(500) NOT NULL,          -- human line ("Stage NEW -> CONTACTED", "Tagged DND")
  detail VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_activity_lead (lead_id, created_at),
  KEY ix_activity_customer (customer_mobile, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
