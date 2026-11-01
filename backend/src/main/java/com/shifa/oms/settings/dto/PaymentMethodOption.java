package com.shifa.oms.settings.dto;

import com.shifa.oms.settings.BankAccountType;
import com.shifa.oms.settings.PaymentMethod;

/**
 * Active payment method as offered to order-entry roles.
 *
 * @param autoAccountId the receiving account to pre-select when {@code linkedAccountType} is set
 *                      (null when there is no link, or no active account of that purpose exists)
 */
public record PaymentMethodOption(
        String code,
        String label,
        boolean requiresAccount,
        BankAccountType linkedAccountType,
        Long autoAccountId,
        boolean defaultMethod) {

    public static PaymentMethodOption from(PaymentMethod m, Long autoAccountId) {
        return new PaymentMethodOption(m.getCode(), m.getLabel(), m.isRequiresAccount(),
                m.getLinkedAccountType(), autoAccountId, m.isDefaultMethod());
    }
}
