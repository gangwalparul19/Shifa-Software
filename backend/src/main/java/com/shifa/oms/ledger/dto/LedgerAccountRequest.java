package com.shifa.oms.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create payload for a postable ledger account ({@code POST /api/accounting/ledgers}, Reqs 2.1–2.3).
 *
 * <p>A ledger account records a reference to its owning account group (Req 2.1); its nature is
 * derived from that group at read time (Req 2.2) and is therefore never supplied here.
 *
 * @param name           the ledger account name (required, trimmed by the service)
 * @param accountGroupId the owning account group id (required, must exist)
 */
public record LedgerAccountRequest(
        @NotBlank(message = "name is required")
        @Size(max = 150, message = "name must be at most 150 characters")
        String name,

        @NotNull(message = "accountGroupId is required")
        Long accountGroupId
) {
}
