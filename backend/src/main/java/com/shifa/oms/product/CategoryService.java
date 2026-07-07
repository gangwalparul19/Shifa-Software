package com.shifa.oms.product;

import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.product.dto.CategoryRequest;
import com.shifa.oms.product.dto.CategoryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Category application service (Catalog &amp; Discovery).
 *
 * <p>Owns the public read of active categories and the admin CRUD
 * (create/update/list/soft-deactivate). Name and slug are unique; a duplicate is
 * rejected with a 409. On create a blank slug is derived from the name.
 */
@Service
public class CategoryService {

    /** Stable error codes for category conflicts (409). */
    static final String DUPLICATE_NAME_CODE = "DUPLICATE_CATEGORY_NAME";
    static final String DUPLICATE_SLUG_CODE = "DUPLICATE_CATEGORY_SLUG";

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /** Active categories for the public storefront, ordered for display. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> publicList() {
        return categoryRepository.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    /** All categories (active + inactive) for the admin management grid. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> adminList() {
        return categoryRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    /** Creates a category, deriving a slug from the name when none is supplied. */
    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        String name = request.name().trim();
        String slug = resolveSlug(request.slug(), name);

        if (categoryRepository.existsByName(name)) {
            throw new DuplicateResourceException(DUPLICATE_NAME_CODE,
                    "A category named '" + name + "' already exists.");
        }
        if (categoryRepository.existsBySlug(slug)) {
            throw new DuplicateResourceException(DUPLICATE_SLUG_CODE,
                    "A category with slug '" + slug + "' already exists.");
        }

        Category category = new Category(
                name,
                slug,
                normalize(request.description()),
                request.sortOrder() == null ? 0 : request.sortOrder(),
                request.active() == null || request.active());
        return CategoryResponse.from(categoryRepository.save(category));
    }

    /** Updates a category's fields; changing name/slug to a taken value is a 409. */
    @Transactional
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Category " + id + " does not exist."));

        String name = request.name().trim();
        String slug = resolveSlug(request.slug(), name);

        if (!category.getName().equals(name) && categoryRepository.existsByName(name)) {
            throw new DuplicateResourceException(DUPLICATE_NAME_CODE,
                    "A category named '" + name + "' already exists.");
        }
        if (!category.getSlug().equals(slug) && categoryRepository.existsBySlug(slug)) {
            throw new DuplicateResourceException(DUPLICATE_SLUG_CODE,
                    "A category with slug '" + slug + "' already exists.");
        }

        category.setName(name);
        category.setSlug(slug);
        category.setDescription(normalize(request.description()));
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }
        return CategoryResponse.from(categoryRepository.save(category));
    }

    /** Soft-deactivates a category so it stops appearing in the public list. */
    @Transactional
    public CategoryResponse deactivate(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Category " + id + " does not exist."));
        category.setActive(false);
        return CategoryResponse.from(categoryRepository.save(category));
    }

    private String resolveSlug(String requested, String name) {
        String base = (requested == null || requested.isBlank()) ? name : requested;
        return slugify(base);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Converts free text into a URL-friendly slug: lower-cased ASCII, spaces and
     * punctuation collapsed to single hyphens, ampersands spelled "and".
     */
    static String slugify(String value) {
        String ampersands = value.replace("&", " and ");
        String normalized = Normalizer.normalize(ampersands, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String slug = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return slug.isEmpty() ? "category" : slug;
    }
}
