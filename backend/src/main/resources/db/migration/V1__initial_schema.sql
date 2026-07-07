-- =============================================================================
-- Shifa Herbal Remedies OMS - initial schema (V1)
-- Engine: InnoDB, Charset: utf8mb4. Monetary columns use DECIMAL(12,2).
-- Enumerations are stored as VARCHAR and validated by application-level enums.
-- =============================================================================

-- ------------------------------------------------------------------ users ----
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------------- products ----
CREATE TABLE products (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    sku         VARCHAR(64)   NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    description TEXT          NULL,
    mrp         DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    sale_price  DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    visibility  VARCHAR(10)   NOT NULL DEFAULT 'HIDDEN',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT uq_products_sku UNIQUE (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------- product_images ----
CREATE TABLE product_images (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    product_id BIGINT       NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    published  BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_product_images PRIMARY KEY (id),
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_product_images_product ON product_images (product_id);

-- --------------------------------------------------- courier_companies ----
CREATE TABLE courier_companies (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    name                  VARCHAR(150) NOT NULL,
    tracking_url_template VARCHAR(300) NULL,
    CONSTRAINT pk_courier_companies PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------- orders ----
CREATE TABLE orders (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    order_code           VARCHAR(30)   NOT NULL,
    source               VARCHAR(20)   NOT NULL,
    created_by           BIGINT        NULL,
    customer_name        VARCHAR(100)  NOT NULL,
    customer_mobile      VARCHAR(10)   NOT NULL,
    address_line         VARCHAR(250)  NOT NULL,
    city                 VARCHAR(100)  NOT NULL,
    state                VARCHAR(100)  NOT NULL,
    postal_code          VARCHAR(6)    NOT NULL,
    total_amount         DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    amount_received      DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    remaining_amount     DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    cod_amount           DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    payment_status       VARCHAR(20)   NOT NULL,
    order_status         VARCHAR(30)   NOT NULL,
    customer_outstanding DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    rejection_reason     VARCHAR(500)  NULL,
    payment_screenshot_key VARCHAR(512) NULL,
    version              BIGINT        NOT NULL DEFAULT 0,
    created_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uq_orders_order_code UNIQUE (order_code),
    CONSTRAINT fk_orders_created_by FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_orders_customer_mobile ON orders (customer_mobile);
CREATE INDEX ix_orders_order_status    ON orders (order_status);
CREATE INDEX ix_orders_created_by      ON orders (created_by);
CREATE INDEX ix_orders_created_at      ON orders (created_at);
-- order_code already has a unique index (uq_orders_order_code) supporting lookups.

-- ------------------------------------------------------------- line_items ----
CREATE TABLE line_items (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    order_id     BIGINT        NOT NULL,
    product_id   BIGINT        NULL,
    product_name VARCHAR(200)  NOT NULL,
    quantity     INT           NOT NULL,
    rate         DECIMAL(12,2) NOT NULL,
    line_total   DECIMAL(12,2) NOT NULL,
    CONSTRAINT pk_line_items PRIMARY KEY (id),
    CONSTRAINT fk_line_items_order   FOREIGN KEY (order_id)   REFERENCES orders (id),
    CONSTRAINT fk_line_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_line_items_quantity CHECK (quantity BETWEEN 1 AND 999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_line_items_order ON line_items (order_id);

-- --------------------------------------------------------------- payments ----
CREATE TABLE payments (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    order_id        BIGINT        NOT NULL,
    amount_received DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    screenshot_key  VARCHAR(512)  NULL,
    captured_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_payments_order ON payments (order_id);

-- --------------------------------------------------------- status_history ----
CREATE TABLE status_history (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    from_status VARCHAR(30)  NULL,
    to_status   VARCHAR(30)  NOT NULL,
    actor       VARCHAR(150) NOT NULL,
    source      VARCHAR(20)  NOT NULL,
    changed_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_status_history PRIMARY KEY (id),
    CONSTRAINT fk_status_history_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_status_history_order ON status_history (order_id);

-- --------------------------------------------------------- courier_records ----
CREATE TABLE courier_records (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    order_id           BIGINT       NOT NULL,
    courier_company_id BIGINT       NULL,
    awb                VARCHAR(64)  NULL,
    shipping_label_key VARCHAR(512) NULL,
    estimated_delivery DATE         NULL,
    last_courier_status VARCHAR(40) NULL,
    CONSTRAINT pk_courier_records PRIMARY KEY (id),
    CONSTRAINT uq_courier_records_order UNIQUE (order_id),
    CONSTRAINT fk_courier_records_order   FOREIGN KEY (order_id)           REFERENCES orders (id),
    CONSTRAINT fk_courier_records_company FOREIGN KEY (courier_company_id) REFERENCES courier_companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_courier_records_awb ON courier_records (awb);

-- ------------------------------------------------------------ receivables ----
CREATE TABLE receivables (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    order_id           BIGINT        NOT NULL,
    courier_company_id BIGINT        NULL,
    type               VARCHAR(20)   NOT NULL,
    amount             DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    settled            BOOLEAN       NOT NULL DEFAULT FALSE,
    settled_date       DATE          NULL,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_receivables PRIMARY KEY (id),
    CONSTRAINT fk_receivables_order   FOREIGN KEY (order_id)           REFERENCES orders (id),
    CONSTRAINT fk_receivables_company FOREIGN KEY (courier_company_id) REFERENCES courier_companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_receivables_order   ON receivables (order_id);
CREATE INDEX ix_receivables_company ON receivables (courier_company_id);
CREATE INDEX ix_receivables_type    ON receivables (type);

-- ------------------------------------------------------------- cart_items ----
-- customer_id may reference a registered customer or a lightweight session
-- identity, so no FK is enforced on it; the (customer_id, product_id) unique
-- key prevents duplicate cart lines (Req 2.7).
CREATE TABLE cart_items (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    quantity    INT    NOT NULL,
    CONSTRAINT pk_cart_items PRIMARY KEY (id),
    CONSTRAINT uq_cart_items_customer_product UNIQUE (customer_id, product_id),
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_cart_items_quantity CHECK (quantity BETWEEN 1 AND 999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------- wishlist_items ----
CREATE TABLE wishlist_items (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    CONSTRAINT pk_wishlist_items PRIMARY KEY (id),
    CONSTRAINT uq_wishlist_items_customer_product UNIQUE (customer_id, product_id),
    CONSTRAINT fk_wishlist_items_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------------------------------------------- outbox ----
CREATE TABLE outbox (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(40)   NOT NULL,
    aggregate_id    BIGINT        NOT NULL,
    event_type      VARCHAR(40)   NOT NULL,
    payload         JSON          NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts        INT           NOT NULL DEFAULT 0,
    next_attempt_at DATETIME      NULL,
    last_error      VARCHAR(1000) NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_outbox PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_outbox_status_next ON outbox (status, next_attempt_at);

-- ------------------------------------------------------------ backup_runs ----
CREATE TABLE backup_runs (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    started_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME      NULL,
    status      VARCHAR(20)   NOT NULL,
    object_key  VARCHAR(512)  NULL,
    error       VARCHAR(1000) NULL,
    CONSTRAINT pk_backup_runs PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX ix_backup_runs_started ON backup_runs (started_at);
