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

        // ------------------------------------------------------------------
        // Shipment defaults (V49) — the parcel logistics the QuikShipX
        // create-order body requires but Shifa OMS does not hold per order.
        //
        // Every field is optional: null leaves the stored value unchanged, so an
        // older client that does not send them cannot wipe the configuration.
        // Range and enumerated-value checks live in SettingsService rather than
        // here, deliberately in ONE place, because they are property-tested
        // directly against the service and duplicating them would invite drift.
        // ------------------------------------------------------------------

        /** QuikShipX pickup warehouse id, from the QuikShipX dashboard (Req 16.1). */
        @Size(max = 40, message = "shipPickupWarehouseId must be at most 40 characters")
        String shipPickupWarehouseId,

        /** {@code "1"} flyer or {@code "2"} cardboard (Req 16.4). */
        @Size(max = 1, message = "shipPackageType must be a single character")
        String shipPackageType,

        /** {@code "1"} surface or {@code "2"} express (Req 16.4). */
        @Size(max = 1, message = "shipShippingMode must be a single character")
        String shipShippingMode,

        /** Default parcel dead weight in grams, 1..50000 (Req 16.4). */
        Integer shipDeadWeightGrams,

        /** Default parcel length in cm, 1..200 (Req 16.4). */
        Integer shipLengthCm,

        /** Default parcel width in cm, 1..200 (Req 16.4). */
        Integer shipWidthCm,

        /** Default parcel height in cm, 1..200 (Req 16.4). */
        Integer shipHeightCm,

        /** Delivery charge levied by the seller, 0.00..99999.99 (Req 16.4). */
        BigDecimal shipShippingAmount,

        /** Fallback {@code product_category} for a product with no category. */
        @Size(max = 120, message = "shipDefaultCategory must be at most 120 characters")
        String shipDefaultCategory,

        /** Fallback HSN code (V50) for a product line with no HSN; QuikShipX needs &gt;1 char. */
        @Size(max = 20, message = "shipDefaultHsn must be at most 20 characters")
        String shipDefaultHsn) {
}
