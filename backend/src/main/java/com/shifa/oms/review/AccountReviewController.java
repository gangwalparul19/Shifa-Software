package com.shifa.oms.review;

import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.review.dto.ReviewRequest;
import com.shifa.oms.review.dto.ReviewSubmissionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer review submission ({@code POST /api/account/reviews}, Phase C).
 *
 * <p>Lives under the authenticated {@code /api/account/**} area and is restricted
 * to the {@code CUSTOMER} role via method security. The review is created PENDING
 * (awaits admin moderation); a customer re-submitting for the same product
 * updates their existing review. The verified-purchase flag is derived from the
 * customer's order history server-side.
 */
@RestController
@RequestMapping("/api/account/reviews")
@PreAuthorize("hasRole('CUSTOMER')")
public class AccountReviewController {

    private final ReviewService reviewService;
    private final CurrentUserService currentUserService;

    public AccountReviewController(ReviewService reviewService,
                                   CurrentUserService currentUserService) {
        this.reviewService = reviewService;
        this.currentUserService = currentUserService;
    }

    /** Submit (or update) the calling customer's review for a product. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewSubmissionResponse submit(@Valid @RequestBody ReviewRequest request) {
        Long userId = currentUserService.requireCurrentUser().userId();
        return reviewService.submit(userId, request);
    }
}
