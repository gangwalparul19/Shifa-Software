package com.shifa.oms.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for a category (admin). The {@code slug} is optional on
 * create — when blank the service derives a URL-friendly slug from the name.
 */
public record CategoryRequest(
        @NotBlank(message = "name is required")
        @Size(max = 120, message = "name must be at most 120 characters")
        String name,

        @Size(max = 140, message = "slug must be at most 140 characters")
        String slug,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        Integer sortOrder,

        Boolean active
) {
}
