package com.shifa.oms.settings;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.settings.dto.SettingsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the single-row company + GST {@link AppSettings}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@link #getSettings()} — load the single row, creating it with defaults
 *       (GST disabled) if it is missing so callers never see an empty result;</li>
 *   <li>{@link #update(SettingsRequest)} — persist the edited configuration,
 *       validating the GSTIN loosely (15 characters) only when GST is enabled so
 *       saving with GST disabled is never hard-blocked.</li>
 * </ul>
 *
 * <p>The invoice module reads {@link #getSettings()} to decide whether to render
 * a plain invoice or a GST tax invoice.
 */
@Service
public class SettingsService {

    /** GSTIN is exactly 15 characters (loose check only, applied when GST is on). */
    static final int GSTIN_LENGTH = 15;

    /** Standard IFSC format: 4 letters, a literal {@code 0}, then 6 alphanumerics. */
    private static final java.util.regex.Pattern IFSC_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z]{4}0[A-Za-z0-9]{6}$");

    /** GST slabs must be numeric percentages in the range 0..28. */
    private static final java.math.BigDecimal MAX_GST_SLAB = new java.math.BigDecimal("28");

    private final AppSettingsRepository repository;

    public SettingsService(AppSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * Loads the single settings row, creating and persisting a defaults row
     * (GST disabled) if none exists yet.
     */
    @Transactional
    public AppSettings getSettings() {
        return repository.findById(AppSettings.SINGLETON_ID)
                .orElseGet(() -> repository.save(AppSettings.defaults()));
    }

    /**
     * Applies the update to the single settings row. When GST is enabled the
     * GSTIN must be present and 15 characters; otherwise the GSTIN is accepted
     * as-is (may be blank).
     *
     * @param request the validated update payload
     * @return the persisted settings
     */
    @Transactional
    public AppSettings update(SettingsRequest request) {
        boolean gstEnabled = Boolean.TRUE.equals(request.gstEnabled());
        String gstin = trimToNull(request.gstin());
        if (gstEnabled) {
            if (gstin == null) {
                throw new ValidationException("GSTIN is required when GST is enabled.");
            }
            if (gstin.length() != GSTIN_LENGTH) {
                throw new ValidationException("GSTIN must be exactly " + GSTIN_LENGTH
                        + " characters when GST is enabled.");
            }
        }

        String bankIfsc = trimToNull(request.bankIfsc());
        if (bankIfsc != null && !IFSC_PATTERN.matcher(bankIfsc).matches()) {
            throw new ValidationException(
                    "bankIfsc must be a valid IFSC (e.g. HDFC0001234) when provided.");
        }
        String gstSlabs = normaliseGstSlabs(request.gstSlabs());

        AppSettings settings = getSettings();
        settings.setGstEnabled(gstEnabled);
        settings.setGstin(gstin);
        settings.setLegalName(request.legalName().trim());
        settings.setAddressLine(trimToNull(request.addressLine()));
        settings.setCity(trimToNull(request.city()));
        settings.setState(trimToNull(request.state()));
        settings.setStateCode(trimToNull(request.stateCode()));
        settings.setGstRatePercent(request.gstRatePercent());
        settings.setPricesIncludeGst(Boolean.TRUE.equals(request.pricesIncludeGst()));
        settings.setInvoiceFooterNote(trimToNull(request.invoiceFooterNote()));
        settings.setContactPhone(trimToNull(request.contactPhone()));
        settings.setContactEmail(trimToNull(request.contactEmail()));
        if (request.lowStockThreshold() != null) {
            settings.setLowStockThreshold(request.lowStockThreshold());
        }
        settings.setInvoiceNumberPrefix(trimToNull(request.invoiceNumberPrefix()));
        settings.setInvoiceTerms(trimToNull(request.invoiceTerms()));
        settings.setBankName(trimToNull(request.bankName()));
        settings.setBankAccountName(trimToNull(request.bankAccountName()));
        settings.setBankAccountNumber(trimToNull(request.bankAccountNumber()));
        settings.setBankIfsc(bankIfsc != null ? bankIfsc.toUpperCase(java.util.Locale.ROOT) : null);
        settings.setBankBranch(trimToNull(request.bankBranch()));
        settings.setGstSlabs(gstSlabs);
        return repository.save(settings);
    }

    /**
     * Validates and normalises a comma-separated GST-slabs string: each entry must
     * be numeric and in the range 0..28. Returns the trimmed, comma-joined slabs
     * (blanks removed), or {@code null} when the input is blank.
     *
     * @throws ValidationException when a slab is non-numeric or out of range
     */
    private String normaliseGstSlabs(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        java.util.List<String> slabs = new java.util.ArrayList<>();
        for (String part : trimmed.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            java.math.BigDecimal slab;
            try {
                slab = new java.math.BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new ValidationException("gstSlabs must contain numeric values only (e.g. 0,5,12,18,28).");
            }
            if (slab.signum() < 0 || slab.compareTo(MAX_GST_SLAB) > 0) {
                throw new ValidationException("gstSlabs values must be between 0 and 28.");
            }
            slabs.add(slab.stripTrailingZeros().toPlainString());
        }
        return slabs.isEmpty() ? null : String.join(",", slabs);
    }

    /**
     * Persists the uploaded company-logo storage key on the settings row (used by
     * {@link CompanyLogoService} after storing the image via the storage service).
     *
     * @param logoObjectKey the opaque storage key, or {@code null} to clear
     * @return the persisted settings
     */
    @Transactional
    public AppSettings updateLogoKey(String logoObjectKey) {
        AppSettings settings = getSettings();
        settings.setLogoObjectKey(logoObjectKey);
        return repository.save(settings);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
