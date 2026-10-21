package com.shifa.oms.settings.dto;

import com.shifa.oms.settings.BankAccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin create/update payload for a receiving or settlement account. */
public record BankAccountRequest(
        @NotBlank @Size(max = 100) String displayName,
        @NotNull BankAccountType accountType,
        @Size(max = 120) String bankName,
        @Size(max = 120) String accountHolderName,
        @Size(max = 40) String accountNumber,
        @Size(max = 20) String ifsc,
        @Size(max = 120) String branch,
        @Size(max = 120) String upiId,
        Long ledgerAccountId,
        boolean active,
        boolean defaultAccount,
        int sortOrder,
        @Size(max = 500) String internalNote) {
}
