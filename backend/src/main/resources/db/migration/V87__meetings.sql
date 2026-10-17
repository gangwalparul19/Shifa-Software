-- CRM expansion Phase 2, Task 11 (design §2.3): scheduled customer meetings
-- owned by a CRM_User, optionally linked to a lead/customer, with a start time,
-- mode, location, notes, and an outcome (Req 9). Additive.
CREATE TABLE meetings (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  lead_id BIGINT NULL,
  customer_mobile VARCHAR(10) NULL,
  start_at DATETIME NOT NULL,
  mode VARCHAR(20) NULL,            -- CALL | VIDEO | IN_PERSON
  location VARCHAR(200) NULL,
  notes VARCHAR(1000) NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED | DONE | CANCELLED
  outcome VARCHAR(1000) NULL,
  reminded_on DATE NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_meetings_owner (owner_user_id, start_at),
  KEY ix_meetings_start (start_at, status),
  CONSTRAINT fk_meetings_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
