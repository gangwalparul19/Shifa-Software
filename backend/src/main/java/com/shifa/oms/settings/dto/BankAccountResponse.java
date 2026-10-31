package com.shifa.oms.settings.dto;

import com.shifa.oms.settings.BankAccount;
import com.shifa.oms.settings.BankAccountType;

/** Admin projection of a configured account. */
public record BankAccountResponse(
        Long id,
        String displayName,
        BankAccountType accountType,
        String bankName,
        String accountHolderName,
        String maskedAccountNumber,
        String ifsc,
        String branch,
        String upiId,
        Long ledgerAccountId,
        boolean active,
        boolean defaultAccount,
        int sortOrder,
        String internalNote) {

    public static BankAccountResponse from(BankAccount a) {
        return new BankAccountResponse(a.getId(), a.getDisplayName(), a.getAccountType(), a.getBankName(),
                a.getAccountHolderName(), a.maskedAccountNumber(), a.getIfsc(),
                a.getBranch(), a.getUpiId(), a.getLedgerAccountId(), a.isActive(), a.isDefaultAccount(),
                a.getSortOrder(), a.getInternalNote());
    }
}
