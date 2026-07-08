package com.shifa.oms.product;

import com.shifa.oms.product.dto.CategoryRequest;
import com.shifa.oms.product.dto.CategoryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin category management (Catalog &amp; Discovery).
 *
 * <p>Mutations (create/update/deactivate) are restricted to the {@code ADMIN}
 * role via the class-level {@code @PreAuthorize}; unauthenticated callers get
 * 401 and non-admins 403. The read-only list additionally allows the
 * {@code SALESPERSON} role via method-level {@code @PreAuthorize} so the
 * salesperson Products page can populate its category filter (products read
 * access is ADMIN + SALESPERSON); mutations stay ADMIN-only.
 */
@RestController
@RequestMapping("/api/admin/categories")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** Lists ALL categories — active and inactive — for the management grid. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SALESPERSON')")
    public List<CategoryResponse> list() {
        return categoryService.adminList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request);
    }

    @PutMapping("/{id}")
    public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    /** Soft-deactivates a category (it disappears from the public list). */
    @DeleteMapping("/{id}")
    public CategoryResponse deactivate(@PathVariable Long id) {
        return categoryService.deactivate(id);
    }
}
