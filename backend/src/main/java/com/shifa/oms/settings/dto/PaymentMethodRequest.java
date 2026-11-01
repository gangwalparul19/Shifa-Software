package com.shifa.oms.settings.dto;

import com.shifa.oms.settings.BankAccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update payload for a payment method. The code is generated from the label on
 * create and never changes afterwards, so it is not part of the request.
 *
 * @param requiresAccount    the salesperson must name the receiving account; forced on when
 *                           {@code linkedAccountType} is set
 * @param linkedAccountType  when set, the receiving account is auto-selected from the active
 *                           accounts of this purpose (e.g. {@code SHOPIFY_SETTLEMENT})
 */
public record PaymentMethodRequest(
        @NotBlank @Size(max = 60) String label,
        boolean requiresAccount,
        BankAccountType linkedAccountType,
        boolean active,
        boolean defaultMethod,
        int sortOrder) {
}
