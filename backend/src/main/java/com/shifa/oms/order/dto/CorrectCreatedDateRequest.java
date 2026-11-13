package com.shifa.oms.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** ADMIN-only correction of the calendar date on which an order was punched. */
public record CorrectCreatedDateRequest(
        @NotNull(message = "correctedDate is required")
        LocalDate correctedDate,

        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason) {
}
