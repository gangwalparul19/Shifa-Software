-- Customizable WhatsApp message templates (managed by ADMIN / ACCOUNTANT / TEAM_LEAD).
-- Templates carry a free-text body with {placeholders} rendered client-side when a
-- salesperson taps a quick-message on the order / customer screens. Seeded with the
-- four built-in defaults so existing behaviour is preserved after migration.
CREATE TABLE whatsapp_templates (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_key    VARCHAR(60)   NOT NULL,
    title           VARCHAR(120)  NOT NULL,
    body            VARCHAR(2000) NOT NULL,
    icon            VARCHAR(40)   NOT NULL DEFAULT 'ti-message-dots',
    active          TINYINT(1)    NOT NULL DEFAULT 1,
    sort_order      INT           NOT NULL DEFAULT 0,
    created_by      BIGINT        NULL,
    created_by_name VARCHAR(150)  NULL,
    created_at      DATETIME      NOT NULL,
    updated_at      DATETIME      NULL,
    CONSTRAINT ux_whatsapp_templates_key UNIQUE (template_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO whatsapp_templates (template_key, title, body, icon, active, sort_order, created_at) VALUES
('confirm', 'Confirm order',
 'Hi {name}, thank you for your order {orderCode} with {brand}. Order total: {total}. We''ll keep you updated on dispatch.',
 'ti-checkbox', 1, 1, NOW()),
('address', 'Ask address',
 'Hi {name}, this is {brand}. To ship your order, please share your full delivery address with PIN code and a preferred delivery time. Thank you!',
 'ti-map-pin', 1, 2, NOW()),
('payment', 'Payment reminder',
 'Hi {name}, a gentle reminder from {brand} regarding your order {orderCode}. Balance due: {remaining}. You can pay via the link/UPI we shared. Thank you!',
 'ti-cash', 1, 3, NOW()),
('followup', 'Follow-up',
 'Hi {name}, this is {brand}. Just checking in - would you like to reorder your favourites or try something new? Happy to help with any questions.',
 'ti-message-dots', 1, 4, NOW());
