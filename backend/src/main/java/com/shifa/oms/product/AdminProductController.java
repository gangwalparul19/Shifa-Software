package com.shifa.oms.product;

import com.shifa.oms.common.PageRequests;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.product.dto.ProductRequest;
import com.shifa.oms.product.dto.ProductResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin product CRUD (Req 6.1, 6.2, 6.3, 6.4).
 *
 * <p>Authorization is applied at the METHOD level (no class-level rule) so the
 * matrix is explicit per endpoint: the READ endpoints (list, paged list, single
 * detail) allow {@code hasAnyRole('ADMIN','SALESPERSON')}, giving a salesperson
 * read-only catalog access (their bottom-nav Products tab), while the mutations
 * (create/update) require {@code hasRole('ADMIN')}. Unauthenticated callers get
 * 401 and non-admins attempting a write get 403 (rendered as the standard error
 * envelope). A duplicate SKU is rejected with a 409 duplicate-SKU error.
 */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    /** Whitelist of API sort fields → JPA properties for the products table. */
    private static final Map<String, String> SORT_WHITELIST = Map.of(
            "name", "name",
            "sku", "sku",
            "salePrice", "salePrice",
            "mrp", "mrp",
            "stockQuantity", "stockQuantity",
            "createdAt", "createdAt");

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "name");

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * Lists ALL products — published and hidden — for the admin management grid
     * (Req 6.3, 6.4). This differs from the public catalog, which only returns
     * published products.
     *
     * <p>Retained as-is (returns a bare array) for backward compatibility with
     * existing callers; the Wave 2 paged table uses {@link #page} instead.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SALESPERSON')")
    public List<ProductResponse> list() {
        return productService.adminList();
    }

    /**
     * Server-side paged / sorted / filtered products list backing the Wave 2
     * admin table (ROADMAP 2.2). Returns the {@link PageResponse} envelope over
     * ALL products (published + hidden).
     *
     * @param q          substring over name / SKU (optional)
     * @param category   category slug or numeric id (optional)
     * @param visibility PUBLISHED / HIDDEN (optional)
     * @param stockStatus IN_STOCK / LOW_STOCK / OUT_OF_STOCK (optional)
     * @param page       zero-based page index (default 0)
     * @param size       page size (default 20, capped at 100)
     * @param sort       {@code field,dir} — one of name/sku/salePrice/mrp/stockQuantity/createdAt
     */
    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('ADMIN','SALESPERSON')")
    public PageResponse<ProductResponse> page(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) ProductVisibility visibility,
            @RequestParam(required = false) StockStatus stockStatus,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORT_WHITELIST, DEFAULT_SORT);
        return PageResponse.of(
                productService.adminList(q, category, visibility, stockStatus, pageable));
    }

    /** Returns a single product for editing, regardless of visibility (Req 6.3). */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SALESPERSON')")
    public ProductResponse get(@PathVariable Long id) {
        return productService.adminDetail(id);
    }

    /** Creates a product (Req 6.1, 6.2). ADMIN-only. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse create(@Valid @RequestBody ProductRequest request) {
        return productService.create(request);
    }

    /** Updates a product, including its visibility flag (Req 6.3, 6.4). ADMIN-only. */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }
}
