-- =============================================================================
-- Shifa Herbal Remedies OMS - admin notification recipient addressing (V24)
--
-- Role-Based Order Workflow, data model (design §3.3, §3.4).
--
-- Today admin_notifications (V16) has no recipient targeting and the console is
-- admin-only. To make in-app notifications addressable per staff role / user
-- (Req 13.3, 13.4, and the per-event recipients in Req 7/8/9/10/11):
--
--  * recipient_role    - when set (and recipient_user_id NULL) the notification
--                        is addressed to every active user holding that role
--                        (Role enum name, VARCHAR(20)) (Req 13.4).
--  * recipient_user_id - when set, addressed to that specific user (e.g. the
--                        creating salesperson, Req 7.3).
--
-- Back-compat: existing rows have both NULL -> treated as "legacy admin
-- broadcast" and remain visible to ADMIN. The composite index backs the
-- per-user / per-role unread query. Additive and nullable, safe on V22.
-- Conventions match V16 (InnoDB, utf8mb4).
-- =============================================================================

ALTER TABLE admin_notifications ADD COLUMN recipient_role    VARCHAR(20) NULL AFTER severity;
ALTER TABLE admin_notifications ADD COLUMN recipient_user_id BIGINT      NULL AFTER recipient_role;

CREATE INDEX ix_admin_notifications_recipient
    ON admin_notifications (recipient_role, recipient_user_id, read_flag);
