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

    /** Upper bound for the seller-levied delivery charge (Req 16.4). */
    private static final java.math.BigDecimal MAX_SHIPPING_AMOUNT = new java.math.BigDecimal("99999.99");

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
        applyShipmentDefaults(settings, request);
        return repository.save(settings);
    }

    /**
     * Applies the update and reports which shipment defaults changed, so the caller
     * can name them in a single audit event (Req 16.3).
     *
     * <p>Kept separate from {@link #update(SettingsRequest)} so the existing return
     * type and its callers stay untouched.
     */
    @Transactional
    public UpdateResult updateWithChanges(SettingsRequest request) {
        java.util.List<String> changes = describeShipmentDefaultChanges(request);
        return new UpdateResult(update(request), changes);
    }

    /** The outcome of an update: the persisted row plus a human-readable change list. */
    public record UpdateResult(AppSettings settings, java.util.List<String> shipmentDefaultChanges) {
    }

    /**
     * Compares the requested shipment defaults against the stored ones, describing
     * each genuine change. Read before the write so the "from" values are the old ones.
     */
    private java.util.List<String> describeShipmentDefaultChanges(SettingsRequest request) {
        AppSettings current = getSettings();
        java.util.List<String> changes = new java.util.ArrayList<>();
        noteChange(changes, "pickup warehouse", current.getShipPickupWarehouseId(),
                trimToNull(request.shipPickupWarehouseId()));
        noteChange(changes, "package type", current.getShipPackageType(),
                trimToNull(request.shipPackageType()));
        noteChange(changes, "shipping mode", current.getShipShippingMode(),
                trimToNull(request.shipShippingMode()));
        noteChange(changes, "dead weight (g)", current.getShipDeadWeightGrams(), request.shipDeadWeightGrams());
        noteChange(changes, "length (cm)", current.getShipLengthCm(), request.shipLengthCm());
        noteChange(changes, "width (cm)", current.getShipWidthCm(), request.shipWidthCm());
        noteChange(changes, "height (cm)", current.getShipHeightCm(), request.shipHeightCm());
        noteChange(changes, "shipping amount", current.getShipShippingAmount(), request.shipShippingAmount());
        noteChange(changes, "default category", current.getShipDefaultCategory(),
                trimToNull(request.shipDefaultCategory()));
        noteChange(changes, "default HSN", current.getShipDefaultHsn(),
                trimToNull(request.shipDefaultHsn()));
        return changes;
    }

    private static void noteChange(java.util.List<String> changes, String label, Object from, Object to) {
        // A null request value means "leave unchanged", so it is never a change.
        if (to == null) {
            return;
        }
        boolean same = from instanceof java.math.BigDecimal a && to instanceof java.math.BigDecimal b
                ? a.compareTo(b) == 0
                : java.util.Objects.equals(from, to);
        if (!same) {
            changes.add(label + " " + (from == null ? "(unset)" : from) + " -> " + to);
        }
    }

    /**
     * Validates and applies the shipment defaults that back the QuikShipX
     * create-order body (Req 16.4, 16.5).
     *
     * <p>Every field is optional: {@code null} leaves the stored value alone, so a
     * client that does not know about these fields cannot wipe them. Validation
     * lives here rather than in the DTO so there is exactly one definition of the
     * permitted ranges, and so it can be property-tested against the service.
     *
     * <p>{@code shipPickupWarehouseId} is intentionally allowed to stay unset —
     * publication is blocked with an admin alert instead, because guessing a
     * warehouse would ship parcels from the wrong place.
     */
    private void applyShipmentDefaults(AppSettings settings, SettingsRequest request) {
        String warehouseId = trimToNull(request.shipPickupWarehouseId());
        if (warehouseId != null) {
            settings.setShipPickupWarehouseId(warehouseId);
        }

        String packageType = trimToNull(request.shipPackageType());
        if (packageType != null) {
            requireOneOf("shipPackageType", packageType, "1", "2");
            settings.setShipPackageType(packageType);
        }

        String shippingMode = trimToNull(request.shipShippingMode());
        if (shippingMode != null) {
            requireOneOf("shipShippingMode", shippingMode, "1", "2");
            settings.setShipShippingMode(shippingMode);
        }

        if (request.shipDeadWeightGrams() != null) {
            settings.setShipDeadWeightGrams(
                    requireInRange("shipDeadWeightGrams", request.shipDeadWeightGrams(), 1, 50_000));
        }
        if (request.shipLengthCm() != null) {
            settings.setShipLengthCm(requireInRange("shipLengthCm", request.shipLengthCm(), 1, 200));
        }
        if (request.shipWidthCm() != null) {
            settings.setShipWidthCm(requireInRange("shipWidthCm", request.shipWidthCm(), 1, 200));
        }
        if (request.shipHeightCm() != null) {
            settings.setShipHeightCm(requireInRange("shipHeightCm", request.shipHeightCm(), 1, 200));
        }

        if (request.shipShippingAmount() != null) {
            java.math.BigDecimal amount = request.shipShippingAmount();
            if (amount.signum() < 0 || amount.compareTo(MAX_SHIPPING_AMOUNT) > 0) {
                throw new ValidationException(
                        "shipShippingAmount must be between 0.00 and " + MAX_SHIPPING_AMOUNT.toPlainString() + ".");
            }
            settings.setShipShippingAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP));
        }

        String defaultCategory = trimToNull(request.shipDefaultCategory());
        if (defaultCategory != null) {
            settings.setShipDefaultCategory(defaultCategory);
        }

        String defaultHsn = trimToNull(request.shipDefaultHsn());
        if (defaultHsn != null) {
            settings.setShipDefaultHsn(defaultHsn);
        }
    }

    private static void requireOneOf(String field, String value, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw new ValidationException(field + " must be one of " + String.join(", ", allowed) + ".");
    }

    private static int requireInRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new ValidationException(field + " must be between " + min + " and " + max + ".");
        }
        return value;
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
