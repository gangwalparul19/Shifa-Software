-- Extra recipients for the admin report emails (the 8 AM morning brief and the weekly report).
--
-- The report already goes to every active ADMIN user who has an email on their profile, plus the
-- addresses in REPORT_DIGEST_TO on the server. That left out admins with no profile email, and
-- anyone who is not a user at all. An admin can now add addresses under Settings -> Email; they are
-- kept here and used for every later report until they are paused or removed.
--
--   email   the address; the utf8mb4_unicode_ci collation is case-insensitive, so the unique key also
--           stops "Owner@x.com" and "owner@x.com" being added twice
--   label   an optional name shown in the list and in the send dialog (for example the admin's name)
--   active  paused addresses stay in the list but are skipped
--
-- Additive: no existing table is touched.

CREATE TABLE report_recipients (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    email            VARCHAR(255) NOT NULL,
    label            VARCHAR(100) NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by_id    BIGINT       NULL,
    created_by_name  VARCHAR(100) NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_report_recipients PRIMARY KEY (id),
    CONSTRAINT uq_report_recipients_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
