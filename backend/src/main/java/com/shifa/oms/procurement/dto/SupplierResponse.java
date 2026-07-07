package com.shifa.oms.procurement.dto;

import com.shifa.oms.procurement.Supplier;

import java.time.LocalDateTime;

/**
 * Read projection for a supplier ({@code /api/admin/suppliers}). Flat and stable
 * for the admin table.
 */
public record SupplierResponse(
        Long id,
        String name,
        String contactPerson,
        String phone,
        String email,
        String address,
        boolean active,
        LocalDateTime createdAt
) {

    public static SupplierResponse from(Supplier s) {
        return new SupplierResponse(
                s.getId(),
                s.getName(),
                s.getContactPerson(),
                s.getPhone(),
                s.getEmail(),
                s.getAddress(),
                s.isActive(),
                s.getCreatedAt());
    }
}
