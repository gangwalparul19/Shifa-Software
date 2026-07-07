package com.shifa.oms.settings;

import com.shifa.oms.settings.dto.StorefrontConfigResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, read-only storefront configuration endpoint.
 *
 * <p>Exposes {@code GET /api/storefront/config} with a minimal, public-safe
 * projection of the company settings (store name, WhatsApp/contact number,
 * support email) so the storefront can power its engagement features
 * (WhatsApp order handoff, support links) without authentication. It reads the
 * single {@link AppSettings} row via {@link SettingsService} and never returns
 * GST or other sensitive configuration. This route is whitelisted in
 * {@code SecurityConfig} for anonymous {@code GET} access.
 */
@RestController
@RequestMapping("/api/storefront/config")
public class StorefrontConfigController {

    private final SettingsService settingsService;

    public StorefrontConfigController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** Returns the public-safe storefront display configuration. */
    @GetMapping
    public StorefrontConfigResponse get() {
        return StorefrontConfigResponse.from(settingsService.getSettings());
    }
}
