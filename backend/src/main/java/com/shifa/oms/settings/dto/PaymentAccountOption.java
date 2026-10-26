package com.shifa.oms.settings.dto;

import com.shifa.oms.settings.BankAccount;
import com.shifa.oms.settings.BankAccountType;

/** Safe active-account projection returned to order-entry roles. */
public record PaymentAccountOption(
        Long id,
        String displayName,
        BankAccountType accountType,
        String bankName,
        String maskedAccountNumber,
        String upiId) {

    public static PaymentAccountOption from(BankAccount a) {
        return new PaymentAccountOption(a.getId(), a.getDisplayName(), a.getAccountType(),
                a.getBankName(), a.maskedAccountNumber(), a.getUpiId());
    }
}
