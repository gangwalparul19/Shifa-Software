-- Database-backed binary object store for payment screenshots (and later PDFs).
-- Previously objects were written to the local filesystem (LocalStorageService),
-- which is fragile on the server (working-dir permissions) and not part of the
-- DB backup. Storing the bytes here keeps every payment screenshot as a durable,
-- restore-with-the-DB record that can be viewed later for payment confirmation.
--
-- The application still persists only the opaque `storage_key` on the order
-- (orders.payment_screenshot_key); DatabaseStorageService resolves that key to
-- the bytes in this table on demand.
CREATE TABLE stored_files (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    storage_key   VARCHAR(255) NOT NULL,
    filename      VARCHAR(255) NULL,
    content_type  VARCHAR(150) NULL,
    byte_size     BIGINT       NOT NULL,
    content       LONGBLOB     NOT NULL,
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_stored_files_key UNIQUE (storage_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
