package com.shifa.oms.review;

import com.shifa.oms.review.dto.ProductReviewsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public product reviews read ({@code GET /api/catalog/products/{id}/reviews},
 * Phase C). Open to anonymous callers (see the security filter chain, which
 * permits {@code GET /api/catalog/**}); only APPROVED reviews are returned along
 * with the aggregate average + count and a per-star breakdown.
 */
@RestController
@RequestMapping("/api/catalog/products")
public class CatalogReviewController {

    private final ReviewService reviewService;

    public CatalogReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** Approved reviews + aggregate for a product. */
    @GetMapping("/{id}/reviews")
    public ProductReviewsResponse reviews(@PathVariable Long id) {
        return reviewService.publicReviews(id);
    }
}
