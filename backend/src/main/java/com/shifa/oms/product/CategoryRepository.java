package com.shifa.oms.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Category} entities.
 *
 * <p>The public catalog only ever exposes {@link Category#isActive() active}
 * categories, ordered for display; the admin management list sees all of them.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** Active categories for the public storefront, ordered for display. */
    List<Category> findByActiveTrueOrderBySortOrderAscNameAsc();

    /** All categories for the admin management grid, ordered for display. */
    List<Category> findAllByOrderBySortOrderAscNameAsc();

    /** Resolves a category by its URL slug (storefront category landing). */
    Optional<Category> findBySlug(String slug);

    boolean existsByName(String name);

    boolean existsBySlug(String slug);
}
