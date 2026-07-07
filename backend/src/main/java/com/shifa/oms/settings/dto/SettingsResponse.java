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
        String gstSlabs) {

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
                s.getGstSlabs());
    }
}
