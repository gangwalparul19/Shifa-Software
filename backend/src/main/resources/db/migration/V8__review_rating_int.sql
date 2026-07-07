-- =============================================================================
-- Align product_reviews.rating with the JPA entity mapping.
-- The review entity maps `rating` as a Java int (JDBC INTEGER), but the column
-- was originally created as TINYINT, which fails Hibernate schema validation
-- (ddl-auto=validate). Widen it to INT (rating is 1..5, so this is safe and
-- non-destructive). Additive migration — does not edit any prior migration.
-- =============================================================================
ALTER TABLE product_reviews MODIFY COLUMN rating INT NOT NULL;
