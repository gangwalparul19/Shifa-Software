package com.shifa.oms.product;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ProductImage} rows.
 *
 * <p>The {@link Product} aggregate maps its images read-only (the join column is
 * {@code insertable=false/updatable=false}), so image rows are persisted through
 * this repository. Used by the local {@code CatalogSeeder} to attach a primary
 * image to each seeded product.
 */
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {
}
