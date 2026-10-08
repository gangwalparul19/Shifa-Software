-- Self-service staff profile edits with admin approval.
--
-- A staff member can view "My Profile" and submit a change request for their
-- own details, but NOTHING on their `users` row changes until an admin approves
-- the request. Each proposed edit is captured here as a pending row; on approval
-- the admin applies it to `users`, on rejection it is left untouched.
--
-- At most one PENDING request per user is kept (a new submission replaces it).
CREATE TABLE staff_profile_change_requests (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    full_name       VARCHAR(150) NULL,
    email           VARCHAR(150) NULL,
    mobile          VARCHAR(10)  NULL,
    date_of_birth   DATE         NULL,
    address         VARCHAR(500) NULL,
    id_proof_type   VARCHAR(30)  NULL,
    id_proof_number VARCHAR(60)  NULL,
    request_note    VARCHAR(500) NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    review_note     VARCHAR(500) NULL,
    requested_at    DATETIME     NOT NULL,
    reviewed_at     DATETIME     NULL,
    reviewed_by     BIGINT       NULL,
    PRIMARY KEY (id),
    KEY ix_pcr_user_status (user_id, status),
    KEY ix_pcr_status (status),
    CONSTRAINT fk_pcr_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
