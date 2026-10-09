package com.shifa.oms.platform.brand;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * White-label brand configuration bound from {@code app.brand.*}.
 *
 * <p>This is the single, config-driven surface that lets the same build run for
 * a different client (or the shared demo) without a code fork. Every value
 * carries the Shifa Herbal Remedies default so the existing production
 * deployment is unaffected; a reseller overrides them per client via the
 * environment file (e.g. {@code BRAND_NAME}, {@code BRAND_ORDER_CODE_PREFIX}).
 *
 * <p>Note the seller's legal name / address / GSTIN that appear on invoices and
 * labels are separate <em>runtime</em> data in the {@code app_settings} table
 * (edited in Settings), not here. {@code app.brand.*} only covers the chrome:
 * the order-code prefix and the fallback brand name used when
 * {@code app_settings} carries none.
 *
 * @param name            human-readable brand/store name, used as the fallback
 *                        on PDFs/labels/GST reports/emails when
 *                        {@code app_settings.legal_name} is blank
 *                        (default {@code "Shifa Herbal Remedies"})
 * @param orderCodePrefix prefix stamped on every generated order code
 *                        (default {@code "SHR-"}); a per-client/demo deployment
 *                        sets its own so codes never collide across deployments
 */
@ConfigurationProperties(prefix = "app.brand")
public record BrandProperties(String name, String orderCodePrefix) {

    /** Shifa defaults applied when a value is absent/blank (keeps prod unchanged). */
    public BrandProperties {
        if (name == null || name.isBlank()) {
            name = "Shifa Herbal Remedies";
        }
        if (orderCodePrefix == null || orderCodePrefix.isBlank()) {
            orderCodePrefix = "SHR-";
        }
    }

    /** A plain Shifa-default instance for tests / non-Spring construction. */
    public static BrandProperties defaults() {
        return new BrandProperties(null, null);
    }
}
