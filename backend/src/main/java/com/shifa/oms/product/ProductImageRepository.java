package com.shifa.oms.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository for {@link ProductImage} rows.
 *
 * <p>The {@link Product} aggregate maps its images read-only (the join column is
 * {@code insertable=false/updatable=false}), so image rows are persisted through
 * this repository. Used by the local {@code CatalogSeeder} to attach a primary
 * image to each seeded product.
 */
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /**
     * All PUBLISHED images for the given product ids, ordered so the primary
     * image of each product (lowest {@code sort_order}) comes first. Used to
     * batch-load the per-product primary image for a set of products in one
     * query — e.g. resolving the thumbnail for every line of an order detail
     * without an N+1 per line (order module, item 1).
     *
     * <p>Returns an empty list when {@code productIds} is empty; callers should
     * keep only the first image seen per product id.
     */
    @Query("""
            SELECT pi FROM ProductImage pi
            WHERE pi.published = true AND pi.productId IN :productIds
            ORDER BY pi.productId ASC, pi.sortOrder ASC
            """)
    List<ProductImage> findPublishedByProductIds(@Param("productIds") Collection<Long> productIds);
}
