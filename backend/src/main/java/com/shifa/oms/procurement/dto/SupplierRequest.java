package com.shifa.oms.procurement.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create / update payload for a supplier ({@code POST/PUT
 * /api/admin/suppliers}). Only {@code name} is required.
 */
public record SupplierRequest(
        @NotBlank(message = "name is required")
        @Size(max = 150, message = "name must be at most 150 characters")
        String name,

        @Size(max = 150, message = "contactPerson must be at most 150 characters")
        String contactPerson,

        @Size(max = 30, message = "phone must be at most 30 characters")
        String phone,

        @Email(message = "email must be a valid email address")
        @Size(max = 150, message = "email must be at most 150 characters")
        String email,

        @Size(max = 500, message = "address must be at most 500 characters")
        String address
) {
}
