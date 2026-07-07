package com.shifa.oms.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Customer review submission payload ({@code POST /api/account/reviews}).
 *
 * <p>A {@code rating} of 1..5 is required; {@code title} and {@code body} are
 * optional. Bean-validation failures render as the standard 400 error envelope.
 */
public record ReviewRequest(
        @NotNull(message = "productId is required")
        Long productId,

        @NotNull(message = "rating is required")
        @Min(value = 1, message = "rating must be between 1 and 5")
        @Max(value = 5, message = "rating must be between 1 and 5")
        Integer rating,

        @Size(max = 150, message = "title must be at most 150 characters")
        String title,

        @Size(max = 2000, message = "body must be at most 2000 characters")
        String body
) {
}
