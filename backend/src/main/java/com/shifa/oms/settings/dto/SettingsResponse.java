package com.shifa.oms.settings.dto;

import com.shifa.oms.settings.AppSettings;

import java.math.BigDecimal;

/**
 * Read projection of the company + GST {@link AppSettings}, returned by
 * {@code GET /api/admin/settings}.
 */
public record SettingsResponse(
        boolean gstEnabled,
        String gstin,
        String legalName,
        String addressLine,
        String city,
        String state,
        String stateCode,
        BigDecimal gstRatePercent,
        boolean pricesIncludeGst,
        String invoiceFooterNote,
        String contactPhone,
        String contactEmail,
        String logoObjectKey,
        int lowStockThreshold,
        String invoiceNumberPrefix,
        String invoiceTerms,
        String bankName,
        String bankAccountName,
        String bankAccountNumber,
        String bankIfsc,
        String bankBranch,
        String gstSlabs,

        // --- Shipment defaults (V49, Req 16.2) ---
        String shipPickupWarehouseId,
        String shipPackageType,
        String shipShippingMode,
        int shipDeadWeightGrams,
        int shipLengthCm,
        int shipWidthCm,
        int shipHeightCm,
        BigDecimal shipShippingAmount,
        String shipDefaultCategory,
        String shipDefaultHsn,

        /**
         * Whether the shipment defaults are complete enough to publish an order to
         * QuikShipX. False means the pickup warehouse id is unset and publication is
         * blocked, which the Settings page surfaces as a warning (Req 16.2, 16.6).
         */
        boolean shipmentDefaultsComplete) {

    public static SettingsResponse from(AppSettings s) {
        return new SettingsResponse(
                s.isGstEnabled(),
                s.getGstin(),
                s.getLegalName(),
                s.getAddressLine(),
                s.getCity(),
                s.getState(),
                s.getStateCode(),
                s.getGstRatePercent(),
                s.isPricesIncludeGst(),
                s.getInvoiceFooterNote(),
                s.getContactPhone(),
                s.getContactEmail(),
                s.getLogoObjectKey(),
                s.getLowStockThreshold(),
                s.getInvoiceNumberPrefix(),
                s.getInvoiceTerms(),
                s.getBankName(),
                s.getBankAccountName(),
                s.getBankAccountNumber(),
                s.getBankIfsc(),
                s.getBankBranch(),
                s.getGstSlabs(),
                s.getShipPickupWarehouseId(),
                s.getShipPackageType(),
                s.getShipShippingMode(),
                s.getShipDeadWeightGrams(),
                s.getShipLengthCm(),
                s.getShipWidthCm(),
                s.getShipHeightCm(),
                s.getShipShippingAmount(),
                s.getShipDefaultCategory(),
                s.getShipDefaultHsn(),
                s.shipmentDefaultsComplete());
    }
}
