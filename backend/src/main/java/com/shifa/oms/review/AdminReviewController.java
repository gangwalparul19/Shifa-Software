package com.shifa.oms.review;

import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.review.dto.AdminReviewResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin review moderation ({@code /api/admin/reviews}, Phase C).
 *
 * <p>Restricted to the {@code ADMIN} role via method security. Exposes the
 * moderation queue (filterable by {@code status}, default PENDING) and the
 * approve/reject transitions, which stamp the moderating admin + time.
 */
@RestController
@RequestMapping("/api/admin/reviews")
@PreAuthorize("hasRole('ADMIN')")
public class AdminReviewController {

    private final ReviewService reviewService;
    private final CurrentUserService currentUserService;

    public AdminReviewController(ReviewService reviewService,
                                 CurrentUserService currentUserService) {
        this.reviewService = reviewService;
        this.currentUserService = currentUserService;
    }

    /**
     * The moderation queue. {@code status} defaults to {@code PENDING}; pass
     * {@code APPROVED} / {@code REJECTED} to review those, or {@code ALL} to see
     * everything.
     */
    @GetMapping
    public List<AdminReviewResponse> queue(
            @RequestParam(name = "status", required = false, defaultValue = "PENDING") String status) {
        return reviewService.moderationQueue(parseStatus(status));
    }

    /** Approve a review → APPROVED (visible on the storefront). */
    @PostMapping("/{id}/approve")
    public AdminReviewResponse approve(@PathVariable Long id) {
        return reviewService.approve(id, currentUserService.requireCurrentUser().userId());
    }

    /** Reject a review → REJECTED (never shown). */
    @PostMapping("/{id}/reject")
    public AdminReviewResponse reject(@PathVariable Long id) {
        return reviewService.reject(id, currentUserService.requireCurrentUser().userId());
    }

    /** Parses the status filter; {@code ALL}/blank means no filter (null). */
    private ReviewStatus parseStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        try {
            return ReviewStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // Unknown filter value → default to the pending queue.
            return ReviewStatus.PENDING;
        }
    }
}
