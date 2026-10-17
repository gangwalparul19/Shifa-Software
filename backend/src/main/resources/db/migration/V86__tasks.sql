-- CRM expansion Phase 2, Task 10 (design §2.2): CRM to-do tasks owned by a
-- CRM_User, optionally linked to a lead/customer, with a due date/time,
-- priority, and completion state (Req 8). Additive.
CREATE TABLE tasks (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(1000) NULL,
  lead_id BIGINT NULL,
  customer_mobile VARCHAR(10) NULL,
  due_at DATETIME NULL,
  priority VARCHAR(10) NOT NULL DEFAULT 'NORMAL',  -- LOW | NORMAL | HIGH
  status VARCHAR(12) NOT NULL DEFAULT 'OPEN',      -- OPEN | DONE | CANCELLED
  completed_at DATETIME NULL,
  reminded_on DATE NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY ix_tasks_owner_status (owner_user_id, status),
  KEY ix_tasks_due (due_at, status),
  CONSTRAINT fk_tasks_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
