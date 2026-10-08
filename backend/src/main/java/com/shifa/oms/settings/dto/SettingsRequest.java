package com.shifa.oms.settings.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Update payload for the company + GST settings ({@code PUT /api/admin/settings}).
 *
 * <p>Bean-validation here covers only always-applicable constraints (field
 * lengths, non-negative rate). The conditional rule "GSTIN is required and
 * loosely 15 chars when GST is enabled" is enforced in {@code SettingsService}
 * so that saving with GST disabled is never hard-blocked by a missing GSTIN.
 */
public record SettingsRequest(
        @NotNull(message = "gstEnabled is required")
        Boolean gstEnabled,

        @Size(max = 20, message = "gstin must be at most 20 characters")
        String gstin,

        @NotBlank(message = "legalName is required")
        @Size(max = 200, message = "legalName must be at most 200 characters")
        String legalName,

        @Size(max = 250, message = "addressLine must be at most 250 characters")
        String addressLine,

        @Size(max = 100, message = "city must be at most 100 characters")
        String city,

        @Size(max = 100, message = "state must be at most 100 characters")
        String state,

        @Size(max = 4, message = "stateCode must be at most 4 characters")
        String stateCode,

        @NotNull(message = "gstRatePercent is required")
        @DecimalMin(value = "0.00", message = "gstRatePercent must not be negative")
        @DecimalMax(value = "100.00", message = "gstRatePercent must not exceed 100")
        @Digits(integer = 3, fraction = 2, message = "gstRatePercent must be a DECIMAL(5,2) value")
        BigDecimal gstRatePercent,

        @NotNull(message = "pricesIncludeGst is required")
        Boolean pricesIncludeGst,

        @Size(max = 500, message = "invoiceFooterNote must be at most 500 characters")
        String invoiceFooterNote,

        @Size(max = 20, message = "contactPhone must be at most 20 characters")
        String contactPhone,

        @Size(max = 120, message = "contactEmail must be at most 120 characters")
        String contactEmail,

        /**
         * Optional settings-level default low-stock threshold (used when a product
         * has no per-product override). Null leaves the current value unchanged.
         */
        @PositiveOrZero(message = "lowStockThreshold must not be negative")
        Integer lowStockThreshold,

        /** Optional invoice-number prefix (e.g. {@code "SHR/24-25/"}). */
        @Size(max = 40, message = "invoiceNumberPrefix must be at most 40 characters")
        String invoiceNumberPrefix,

        /** Optional multi-line invoice terms &amp; conditions. */
        @Size(max = 2000, message = "invoiceTerms must be at most 2000 characters")
        String invoiceTerms,

        @Size(max = 120, message = "bankName must be at most 120 characters")
        String bankName,

        @Size(max = 120, message = "bankAccountName must be at most 120 characters")
        String bankAccountName,

        @Size(max = 40, message = "bankAccountNumber must be at most 40 characters")
        String bankAccountNumber,

        /**
         * Optional bank IFSC. When present it must match the standard IFSC format
         * (4 letters, {@code 0}, then 6 alphanumerics); the conditional check is
         * enforced in {@code SettingsService} so a blank value is accepted.
         */
        @Size(max = 20, message = "bankIfsc must be at most 20 characters")
        String bankIfsc,

        @Size(max = 120, message = "bankBranch must be at most 120 characters")
        String bankBranch,

        /**
         * Optional comma-separated GST slabs (e.g. {@code "0,5,12,18,28"}). Each
         * value must be numeric in the range 0..28; that is enforced in
         * {@code SettingsService}.
         */
        @Size(max = 100, message = "gstSlabs must be at most 100 characters")
        String gstSlabs,

        /**
         * Optional seller aggregate turnover (₹). Drives the GSTR-1 HSN reporting
         * minimum length (6 digits above ₹5 crore, else 4). Null leaves it unset
         * (4-digit rule).
         */
        @DecimalMin(value = "0.00", message = "aggregateTurnover must not be negative")
        @Digits(integer = 13, fraction = 2, message = "aggregateTurnover must be a DECIMAL(15,2) value")
        BigDecimal aggregateTurnover,

        /**
         * Optional GST filing-calendar reminder look-ahead, in whole days. Resolved
         * to {@code [1, 30]} with a default of {@code 7} by the filing module; null
         * leaves it unset.
         */
        Integer gstReminderWindowDays,

        /**
         * Optional GST reconciliation match tolerance in ₹. Resolved to
         * {@code [0.00, 9999.99]} with a default of {@code 1.00} by the filing
         * module; null leaves it unset.
         */
        @DecimalMin(value = "0.00", message = "gstReconciliationTolerance must not be negative")
        @Digits(integer = 4, fraction = 2, message = "gstReconciliationTolerance must be a DECIMAL(6,2) value")
        BigDecimal gstReconciliationTolerance,

        /**
         * Config-driven order auto-approval switch (DEFAULT OFF). Null is treated
         * as {@code false}. When on, low-value fully-prepaid orders from low-risk
         * customers are auto-approved at creation.
         */
        Boolean autoApproveEnabled,

        /**
         * Maximum order total (₹) eligible for auto-approval. Null/non-positive
         * means nothing qualifies. Capped as a DECIMAL(12,2).
         */
        @DecimalMin(value = "0.00", message = "autoApproveMaxAmount must not be negative")
        @Digits(integer = 10, fraction = 2, message = "autoApproveMaxAmount must be a DECIMAL(12,2) value")
        BigDecimal autoApproveMaxAmount) {
}
