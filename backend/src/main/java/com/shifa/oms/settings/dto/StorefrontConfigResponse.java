package com.shifa.oms.settings.dto;

import com.shifa.oms.settings.AppSettings;

/**
 * Public, non-sensitive storefront display configuration returned by
 * {@code GET /api/storefront/config}.
 *
 * <p>Deliberately minimal and public-safe: it exposes only the few company
 * contact/display values the storefront needs for engagement features (the
 * "Order on WhatsApp" handoff and support links). It is sourced from the same
 * {@link AppSettings} row the admin edits, but never leaks GST/legal internals.
 *
 * @param storeName      the public store/brand name to show to customers
 * @param whatsappNumber the WhatsApp/contact phone the handoff deep-links to,
 *                       or {@code null} when none is configured (the storefront
 *                       falls back to a built-in default)
 * @param supportEmail   the customer support email, or {@code null}
 */
public record StorefrontConfigResponse(
        String storeName,
        String whatsappNumber,
        String supportEmail) {

    /** Projects the public-safe display fields from the settings row. */
    public static StorefrontConfigResponse from(AppSettings s) {
        String storeName = s.getLegalName() == null || s.getLegalName().isBlank()
                ? "Shifa Herbal Remedies"
                : s.getLegalName();
        return new StorefrontConfigResponse(
                storeName,
                s.getContactPhone(),
                s.getContactEmail());
    }
}
