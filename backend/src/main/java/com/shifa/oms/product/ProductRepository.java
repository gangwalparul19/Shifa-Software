package com.shifa.oms.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Product} entities.
 *
 * <p>The catalog/search finders apply the same visibility + substring rule as
 * the pure {@link ProductCatalog} predicate (Req 1.1, 1.3, 6.4), pushed into SQL
 * so the storefront never sees hidden products.
 */
public interface ProductRepository extends JpaRepository<Product, Long>,
        JpaSpecificationExecutor<Product> {

    /** True if a product with this SKU already exists (Req 6.2). */
    boolean existsBySku(String sku);

    /** Finds a product by SKU, used to detect duplicates on update (Req 6.2). */
    Optional<Product> findBySku(String sku);

    /** Published catalog, ordered by name (Req 1.1, 6.4). */
    List<Product> findByVisibilityOrderByNameAsc(ProductVisibility visibility);

    /**
     * All products (published AND hidden), ordered by name, for the admin
     * management grid (Req 6.3, 6.4). Unlike the catalog finder this is not
     * filtered by visibility so an admin can see and manage hidden products.
     */
    List<Product> findAllByOrderByNameAsc();

    /** A single product visible in the storefront, else empty (Req 1.2, 1.7). */
    Optional<Product> findByIdAndVisibility(Long id, ProductVisibility visibility);

    /**
     * Case-insensitive substring search over published products, matching on
     * name OR SKU (Req 1.3). Mirrors {@link ProductCatalog#matches}.
     */
    @Query("""
            SELECT p FROM Product p
            WHERE p.visibility = :visibility
              AND (LOWER(p.name) LIKE CONCAT('%', LOWER(:query), '%')
                   OR LOWER(p.sku) LIKE CONCAT('%', LOWER(:query), '%'))
            ORDER BY p.name ASC
            """)
    List<Product> searchPublished(@Param("visibility") ProductVisibility visibility,
                                  @Param("query") String query);

    /**
     * Case-insensitive substring search over ALL products (published AND hidden)
     * on name OR SKU, ordered by name. Backs the admin global search
     * (ROADMAP 2.2) where an admin must be able to find hidden products too.
     */
    @Query("""
            SELECT p FROM Product p
            WHERE LOWER(p.name) LIKE CONCAT('%', LOWER(:query), '%')
               OR LOWER(p.sku) LIKE CONCAT('%', LOWER(:query), '%')
            ORDER BY p.name ASC
            """)
    List<Product> searchAllByNameOrSku(@Param("query") String query);
}
