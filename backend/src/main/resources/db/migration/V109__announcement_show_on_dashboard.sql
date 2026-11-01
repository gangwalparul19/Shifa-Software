-- Lets an admin pin a staff announcement to the top of the home screen.
--
-- Announcements already appear as a thin, dismissible banner on every page, and many staff
-- dismiss it or never notice it. show_on_dashboard marks the ones the admin wants shown as a
-- prominent card at the top of each user's home screen (dashboard, or the payments / GST screen
-- for the roles that land there). It only matters while the announcement is also active.
--
-- Additive. Existing announcements stay unpinned (FALSE) so nothing changes until an admin
-- pins one.

ALTER TABLE staff_announcements
    ADD COLUMN show_on_dashboard BOOLEAN NOT NULL DEFAULT FALSE AFTER active;
