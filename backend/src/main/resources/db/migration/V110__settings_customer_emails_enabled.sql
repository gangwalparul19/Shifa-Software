-- Switch for the status emails the system sends to CUSTOMERS (order approved, dispatched, delivered).
--
-- OFF by default: from this version no email goes to a customer until an admin turns the switch on
-- (Settings -> Automation). Emails to staff are not affected: the daily/weekly report to the admins and
-- the temporary-password email to the admin who resets a user keep working.
ALTER TABLE app_settings
    ADD COLUMN customer_emails_enabled BOOLEAN NOT NULL DEFAULT FALSE;
