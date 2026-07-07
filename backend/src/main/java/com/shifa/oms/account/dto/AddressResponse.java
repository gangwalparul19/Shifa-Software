package com.shifa.oms.account.dto;

import com.shifa.oms.account.CustomerAddress;

import java.time.LocalDateTime;

/** A saved address as returned by the account address endpoints. */
public record AddressResponse(
        Long id,
        String label,
        String fullName,
        String mobile,
        String addressLine,
        String city,
        String state,
        String postalCode,
        boolean isDefault,
        LocalDateTime createdAt) {

    public static AddressResponse from(CustomerAddress address) {
        return new AddressResponse(
                address.getId(),
                address.getLabel(),
                address.getFullName(),
                address.getMobile(),
                address.getAddressLine(),
                address.getCity(),
                address.getState(),
                address.getPostalCode(),
                address.isDefault(),
                address.getCreatedAt());
    }
}
