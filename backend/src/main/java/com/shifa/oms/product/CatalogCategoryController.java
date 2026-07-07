package com.shifa.oms.product;

import com.shifa.oms.product.dto.CategoryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public category listing (Catalog &amp; Discovery). Open to anonymous callers
 * (see the security filter chain), returning only active categories ordered for
 * display so the storefront can render category navigation and landing pages.
 */
@RestController
@RequestMapping("/api/catalog/categories")
public class CatalogCategoryController {

    private final CategoryService categoryService;

    public CatalogCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public List<CategoryResponse> list() {
        return categoryService.publicList();
    }
}
