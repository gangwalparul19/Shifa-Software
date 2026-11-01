package com.shifa.oms.settings.dto;

import com.shifa.oms.settings.BankAccountType;
import com.shifa.oms.settings.PaymentMethod;

/**
 * Admin projection of a payment method.
 *
 * @param usageCount number of recorded payments that carry this method; a method with payments
 *                   can be deactivated but not deleted
 */
public record PaymentMethodResponse(
        Long id,
        String code,
        String label,
        boolean requiresAccount,
        BankAccountType linkedAccountType,
        boolean active,
        boolean defaultMethod,
        int sortOrder,
        long usageCount) {

    public static PaymentMethodResponse from(PaymentMethod m, long usageCount) {
        return new PaymentMethodResponse(m.getId(), m.getCode(), m.getLabel(), m.isRequiresAccount(),
                m.getLinkedAccountType(), m.isActive(), m.isDefaultMethod(), m.getSortOrder(), usageCount);
    }
}
