-- Team Lead role: assign salespeople to a team lead so the lead can track the
-- orders punched by their team. A salesperson's manager is recorded on their
-- users row via team_lead_id (nullable, self-referencing FK). Additive/nullable
-- and safe on existing seeded data (all rows default to NULL = unassigned).

ALTER TABLE users
    ADD COLUMN team_lead_id BIGINT NULL;

ALTER TABLE users
    ADD CONSTRAINT fk_users_team_lead
        FOREIGN KEY (team_lead_id) REFERENCES users (id)
        ON DELETE SET NULL;

CREATE INDEX ix_users_team_lead ON users (team_lead_id);
