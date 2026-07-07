package com.shifa.oms.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for outbound email, bound from {@code app.mail.*} (Feature E3),
 * mirroring {@link com.shifa.oms.notification.WhatsAppProperties}.
 *
 * @param mode         backend selector: {@code MOCK} (local dev, default, logs only)
 *                     or {@code SMTP} (real send via {@code spring.mail.*})
 * @param from         the From address applied to every outbound message
 * @param digestTo     recipient(s) for the daily sales digest; blank disables the
 *                     digest email (Feature C4)
 * @param brand        branding + theme used by the HTML email templates
 *                     ({@code app.mail.brand.*})
 * @param maxAttempts  maximum send attempts before the {@code EMAIL_NOTIFY} outbox
 *                     event is marked FAILED and an ADMIN alert raised (Req 14.5);
 *                     mirrors {@code app.whatsapp.max-attempts}
 * @param retryBackoff delay before the next email send attempt after a failure;
 *                     mirrors {@code app.whatsapp.retry-backoff}
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(String mode, String from, String digestTo, Brand brand,
                             Integer maxAttempts, Duration retryBackoff) {

    public MailProperties {
        if (mode == null || mode.isBlank()) {
            mode = "MOCK";
        }
        if (from == null || from.isBlank()) {
            from = "no-reply@shifa.local";
        }
        if (digestTo == null) {
            digestTo = "";
        }
        if (brand == null) {
            brand = new Brand(null, null, null, null, null, null, null);
        }
        if (maxAttempts == null || maxAttempts < 1) {
            maxAttempts = 3;
        }
        if (retryBackoff == null) {
            retryBackoff = Duration.ofSeconds(30);
        }
    }

    /** Whether the mock (log-only) backend is selected. */
    public boolean isMock() {
        return "MOCK".equalsIgnoreCase(mode);
    }

    /**
     * Branding + theme configuration for the HTML email templates, bound from
     * {@code app.mail.brand.*}. All values have on-brand defaults so emails
     * render correctly even with no explicit configuration.
     *
     * @param brandName    the store/brand name shown in the header + copy
     *                     (default {@code "Shifa Herbal Remedies"})
     * @param siteUrl      the public storefront URL used for every CTA/link
     *                     (default {@code "http://130.210.52.12"})
     * @param logoUrl      absolute URL of a logo image; blank ⇒ render the styled
     *                     wordmark instead (default blank)
     * @param supportPhone the contact/WhatsApp number shown in the footer
     *                     (default {@code "+91 9302590767"})
     * @param supportEmail the support email shown in the footer; blank ⇒ fall
     *                     back to {@code AppSettings.contactEmail} (default blank)
     * @param primaryColor the deep herbal green theme colour (default {@code "#1b5e20"})
     * @param accentColor  the gold accent colour (default {@code "#c8a24b"})
     */
    public record Brand(
            String brandName,
            String siteUrl,
            String logoUrl,
            String supportPhone,
            String supportEmail,
            String primaryColor,
            String accentColor) {

        public Brand {
            if (brandName == null || brandName.isBlank()) {
                brandName = "Shifa Herbal Remedies";
            }
            if (siteUrl == null || siteUrl.isBlank()) {
                siteUrl = "http://130.210.52.12";
            }
            if (logoUrl == null) {
                logoUrl = "";
            }
            if (supportPhone == null || supportPhone.isBlank()) {
                supportPhone = "+91 9302590767";
            }
            if (supportEmail == null) {
                supportEmail = "";
            }
            if (primaryColor == null || primaryColor.isBlank()) {
                primaryColor = "#1b5e20";
            }
            if (accentColor == null || accentColor.isBlank()) {
                accentColor = "#c8a24b";
            }
        }

        /** Whether a logo image URL is configured (else the wordmark is used). */
        public boolean hasLogo() {
            return logoUrl != null && !logoUrl.isBlank();
        }
    }
}
