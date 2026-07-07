package com.shifa.oms.product;

import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.product.dto.ProductRequest;
import com.shifa.oms.product.dto.ProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Product / catalog application service (design: "Product Module").
 *
 * <p>Owns admin CRUD with SKU uniqueness (Req 6.1, 6.2, 6.3, 6.4) and the public
 * catalog/search/detail reads that expose only published products
 * (Req 1.1, 1.2, 1.3, 1.6, 1.7). Read paths run in a read-only transaction so
 * lazy image collections can be projected to DTOs.
 */
@Service
public class ProductService {

    /** Stable error code for a duplicate-SKU conflict (409). */
    static final String DUPLICATE_SKU_CODE = "DUPLICATE_SKU";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    /**
     * Optional lookup of product rating aggregates (Phase C: reviews). Injected
     * by Spring when the review module is present; may be {@code null} so the
     * product module works in isolation and unit tests need not provide it.
     */
    @Nullable
    private final ProductRatingLookup ratingLookup;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          @Nullable ProductRatingLookup ratingLookup) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.ratingLookup = ratingLookup;
    }

    /**
     * Creates a product, rejecting a duplicate SKU with a 409 (Req 6.1, 6.2).
     */
    @Transactional
    public ProductResponse create(ProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw duplicateSku(request.sku());
        }
        Product product = new Product(
                request.sku(),
                request.name(),
                request.description(),
                request.mrp(),
                request.salePrice(),
                request.visibility());
        product.setHsnCode(normalizeHsn(request.hsnCode()));
        product.setGstRate(request.gstRate());
        applyCatalogFields(product, request);
        return ProductResponse.from(productRepository.save(product));
    }

    /**
     * Updates an existing product's fields and visibility (Req 6.3, 6.4).
     * Changing the SKU to one already used by another product is rejected as a
     * duplicate (Req 6.2).
     */
    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product " + id + " does not exist."));

        if (!product.getSku().equals(request.sku())
                && productRepository.existsBySku(request.sku())) {
            throw duplicateSku(request.sku());
        }

        product.setSku(request.sku());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setMrp(request.mrp());
        product.setSalePrice(request.salePrice());
        product.setHsnCode(normalizeHsn(request.hsnCode()));
        product.setGstRate(request.gstRate());
        product.setVisibility(request.visibility());
        applyCatalogFields(product, request);
        return ProductResponse.from(productRepository.save(product));
    }

    /**
     * Applies the Catalog &amp; Discovery fields (category, stock, featured) from
     * a request onto a product. A {@code null} category id clears the category; a
     * non-null one must reference an existing category (else a 404). Missing
     * stock/flag values default to the safe legacy behaviour (0 stock, tracking
     * off, not featured).
     */
    private void applyCatalogFields(Product product, ProductRequest request) {
        if (request.categoryId() == null) {
            product.setCategory(null);
        } else {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Category " + request.categoryId() + " does not exist."));
            product.setCategory(category);
        }
        product.setStockQuantity(request.stockQuantity() == null ? 0 : request.stockQuantity());
        product.setTrackInventory(Boolean.TRUE.equals(request.trackInventory()));
        product.setLowStockThreshold(request.lowStockThreshold());
        product.setFeatured(Boolean.TRUE.equals(request.featured()));
    }

    /** Trims the optional HSN code to {@code null} when blank so it stays truly optional. */
    private String normalizeHsn(String hsnCode) {
        if (hsnCode == null) {
            return null;
        }
        String trimmed = hsnCode.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * All products (published AND hidden) for the admin management grid, ordered
     * by name (Req 6.3, 6.4). Unlike {@link #catalog()} this is not filtered by
     * visibility so an admin can manage hidden products too.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> adminList() {
        return productRepository.findAllByOrderByNameAsc().stream()
                .map(ProductResponse::from)
                .toList();
    }

    /**
     * Server-side paged / sorted / filtered admin products listing that backs the
     * Wave 2 products table (ROADMAP 2.2). Returns published AND hidden products
     * (like {@link #adminList()}) but as a page, honouring the optional
     * {@code q} / {@code category} / {@code visibility} / {@code stockStatus}
     * filters. The {@code category} param accepts a slug or numeric id; a
     * category that resolves to nothing yields an empty page rather than an
     * error. Ratings are intentionally not attached (the admin grid does not show
     * stars), matching the existing {@link #adminList()} projection.
     *
     * @param q          substring over name / SKU (nullable)
     * @param category   category slug or numeric id (nullable)
     * @param visibility PUBLISHED / HIDDEN (nullable)
     * @param stockStatus derived stock status filter (nullable)
     * @param pageable   page / size / sort
     * @return a page of product responses
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> adminList(String q, String category,
                                           ProductVisibility visibility,
                                           StockStatus stockStatus, Pageable pageable) {
        Long categoryId = null;
        if (category != null && !category.isBlank()) {
            categoryId = resolveCategoryId(category);
            if (categoryId == null) {
                // A category id/slug that resolves to nothing: no products match.
                return Page.empty(pageable);
            }
        }
        Specification<Product> spec =
                ProductListSpecifications.build(q, categoryId, visibility, stockStatus);
        return productRepository.findAll(spec, pageable).map(ProductResponse::from);
    }

    /** Resolves a {@code category} param (slug or numeric id) to a category id, or null. */
    private Long resolveCategoryId(String category) {
        String trimmed = category.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            try {
                Long id = Long.valueOf(trimmed);
                return categoryRepository.findById(id).map(Category::getId).orElse(null);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return categoryRepository.findBySlug(trimmed).map(Category::getId).orElse(null);
    }

    /**
     * A single product for admin editing, regardless of visibility (Req 6.3).
     * Missing products yield a 404.
     */
    @Transactional(readOnly = true)
    public ProductResponse adminDetail(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product " + id + " does not exist."));
        return ProductResponse.from(product);
    }

    /** The published catalog (Req 1.1, 1.6 — empty when no products published). */
    @Transactional(readOnly = true)
    public List<ProductResponse> catalog() {
        return withRatings(
                productRepository.findByVisibilityOrderByNameAsc(ProductVisibility.PUBLISHED));
    }

    /**
     * Case-insensitive substring search over published products on name or SKU
     * (Req 1.3, 1.5 — empty when nothing matches). A blank query returns the
     * full catalog.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> search(String query) {
        if (query == null || query.isBlank()) {
            return catalog();
        }
        return withRatings(
                productRepository.searchPublished(ProductVisibility.PUBLISHED, query.trim()));
    }

    /**
     * Filtered + sorted catalog listing (Catalog &amp; Discovery). Fetches the
     * published products once and applies the pure {@link CatalogFilter} to
     * honour the {@code q}/{@code category}/{@code minPrice}/{@code maxPrice}/
     * {@code inStock}/{@code featured}/{@code sort} parameters. Returns only
     * published products; an empty list when nothing matches.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> catalog(CatalogQuery query) {
        CatalogQuery effective = query == null ? CatalogQuery.all() : query;
        List<Product> published =
                productRepository.findByVisibilityOrderByNameAsc(ProductVisibility.PUBLISHED);
        return withRatings(CatalogFilter.apply(published, effective));
    }

    /**
     * Builds a {@link CatalogQuery} from the raw request parameters and runs the
     * filtered listing. The {@code category} parameter accepts either a slug or a
     * numeric category id (resolved to its slug); an unknown category yields an
     * empty result rather than an error.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> catalog(String q, String category, BigDecimal minPrice,
                                         BigDecimal maxPrice, String sort,
                                         boolean inStockOnly, boolean featuredOnly) {
        String slug = resolveCategorySlug(category);
        // An id/slug that resolves to nothing: no products can match.
        if (category != null && !category.isBlank() && slug == null) {
            return List.of();
        }
        CatalogQuery query = new CatalogQuery(
                q, slug, minPrice, maxPrice, inStockOnly, featuredOnly,
                CatalogSort.fromParam(sort));
        return catalog(query);
    }

    /** Resolves a {@code category} param (slug or numeric id) to a slug, or null. */
    private String resolveCategorySlug(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String trimmed = category.trim();
        // Numeric → treat as a category id and look up its slug.
        if (trimmed.chars().allMatch(Character::isDigit)) {
            try {
                Long id = Long.valueOf(trimmed);
                return categoryRepository.findById(id).map(Category::getSlug).orElse(null);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // Otherwise treat as a slug; confirm it exists so a typo yields empty.
        return categoryRepository.findBySlug(trimmed).map(Category::getSlug).orElse(null);
    }

    /**
     * "You may also like" suggestions for a published product (Catalog &amp;
     * Discovery). The target must itself be published/available (else 404);
     * candidates are the other published products ranked by the
     * {@link RelatedProducts} heuristic (same category first, then featured,
     * excluding the product itself and out-of-stock items).
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> related(Long id, int limit) {
        Product target = productRepository.findByIdAndVisibility(id, ProductVisibility.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product " + id + " is not available."));
        List<Product> candidates =
                productRepository.findByVisibilityOrderByNameAsc(ProductVisibility.PUBLISHED);
        return withRatings(RelatedProducts.select(target, candidates, limit));
    }

    /**
     * Product detail, only when the product is published; otherwise a 404 so
     * hidden/unavailable products are indistinguishable to the storefront
     * (Req 1.2, 1.7).
     */
    @Transactional(readOnly = true)
    public ProductResponse detail(Long id) {
        Product product = productRepository.findByIdAndVisibility(id, ProductVisibility.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product " + id + " is not available."));
        ProductResponse response = ProductResponse.from(product);
        if (ratingLookup == null) {
            return response;
        }
        ProductRatingLookup.ProductRating rating = ratingLookup.ratingFor(product.getId());
        return response.withRating(rating.average(), rating.count());
    }

    /**
     * Maps published products to responses, enriching each with its APPROVED
     * review aggregate (average + count) in a single batch lookup (Phase C). When
     * the review module is absent the responses carry no rating (null/0).
     */
    private List<ProductResponse> withRatings(List<Product> products) {
        if (ratingLookup == null || products.isEmpty()) {
            return products.stream().map(ProductResponse::from).toList();
        }
        Map<Long, ProductRatingLookup.ProductRating> ratings =
                ratingLookup.ratingsFor(products.stream().map(Product::getId).toList());
        return products.stream()
                .map(product -> {
                    ProductRatingLookup.ProductRating rating =
                            ratings.getOrDefault(product.getId(), ProductRatingLookup.ProductRating.NONE);
                    return ProductResponse.from(product).withRating(rating.average(), rating.count());
                })
                .toList();
    }

    private DuplicateResourceException duplicateSku(String sku) {
        return new DuplicateResourceException(
                DUPLICATE_SKU_CODE,
                "A product with SKU '" + sku + "' already exists.");
    }
}
