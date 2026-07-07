package com.shifa.oms.product;

import com.shifa.oms.product.dto.ProductResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public catalog and search endpoints (Req 1.1, 1.2, 1.3, 1.6, 1.7) plus the
 * Catalog &amp; Discovery filtering/sorting and related-products endpoints.
 *
 * <p>These are open to anonymous callers (see the security filter chain) and
 * only ever expose published products. An unavailable/hidden product detail is
 * a 404, so hidden products are indistinguishable from missing ones (Req 1.7).
 */
@RestController
@RequestMapping("/api/catalog/products")
public class CatalogController {

    private final ProductService productService;

    public CatalogController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * The published catalog with optional filtering + sorting (Req 1.1, 1.3,
     * 1.6). All parameters are optional:
     *
     * <ul>
     *   <li>{@code q} — case-insensitive substring search on name/SKU.</li>
     *   <li>{@code category} — category slug or numeric id.</li>
     *   <li>{@code minPrice} / {@code maxPrice} — inclusive sale-price bounds.</li>
     *   <li>{@code sort} — {@code relevance|price_asc|price_desc|name_asc|newest}.</li>
     *   <li>{@code inStock} — when true, exclude out-of-stock products.</li>
     *   <li>{@code featured} — when true, only featured products.</li>
     * </ul>
     *
     * Returns an empty list when nothing matches.
     */
    @GetMapping
    public List<ProductResponse> list(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "minPrice", required = false) BigDecimal minPrice,
            @RequestParam(name = "maxPrice", required = false) BigDecimal maxPrice,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "inStock", required = false, defaultValue = "false") boolean inStock,
            @RequestParam(name = "featured", required = false, defaultValue = "false") boolean featured) {
        return productService.catalog(query, category, minPrice, maxPrice, sort, inStock, featured);
    }

    /** Product detail, only when published (Req 1.2, 1.7). */
    @GetMapping("/{id}")
    public ProductResponse detail(@PathVariable Long id) {
        return productService.detail(id);
    }

    /**
     * "You may also like" suggestions for a published product (Catalog &amp;
     * Discovery). Returns up to {@code limit} (default 4) other published,
     * in-stock products, preferring the same category then featured items.
     */
    @GetMapping("/{id}/related")
    public List<ProductResponse> related(
            @PathVariable Long id,
            @RequestParam(name = "limit", required = false,
                    defaultValue = "" + RelatedProducts.DEFAULT_LIMIT) int limit) {
        return productService.related(id, limit);
    }
}
