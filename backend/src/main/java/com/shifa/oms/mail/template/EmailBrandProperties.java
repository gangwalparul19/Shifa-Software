package com.shifa.oms.mail.template;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Branding + theme configuration for the HTML email templates, bound from
 * {@code app.mail.brand.*} (Part 2).
 *
 * <p>Every value carries an on-brand default so the {@link EmailRenderer}
 * produces correct, fully-branded email even with no explicit configuration.
 * This keeps the renderer a pure function of its config: tests construct a
 * fixed instance directly ({@code new EmailBrandProperties(...)}) and assert on
 * the rendered output without Spring.
 *
 * @param brandName      the store/brand name shown in the header + copy
 *                       (default {@code "Shifa Herbal Remedies"})
 * @param tagline        the short brand tagline shown under the wordmark
 *                       (default {@code "Pure herbal wellness, delivered."})
 * @param siteUrl        the public storefront URL used for every CTA/link
 *                       (default {@code "https://www.shifaherbals.com"})
 * @param logoUrl        absolute URL of a logo image; blank ⇒ render the styled
 *                       wordmark instead of an {@code <img>} (default blank)
 * @param supportEmail   the support email shown in the footer
 *                       (default {@code "support@shifaherbals.com"})
 * @param supportPhone   the contact/WhatsApp number shown in the footer
 *                       (default {@code "+91 93025 90767"})
 * @param primaryColor   the deep herbal green header/theme colour
 *                       (default {@code "#1b5e20"})
 * @param accentColor    the gold accent/CTA colour (default {@code "#c8a44d"})
 * @param footerCredit   the designer/developer credit line shown in the footer
 *                       (default {@code "Designed & Developed by Weblithic"})
 * @param footerCreditUrl the URL the footer credit hyperlinks to
 *                       (default {@code "https://www.weblithic.com/"})
 */
@ConfigurationProperties(prefix = "app.mail.brand")
public record EmailBrandProperties(
        String brandName,
        String tagline,
        String siteUrl,
        String logoUrl,
        String supportEmail,
        String supportPhone,
        String primaryColor,
        String accentColor,
        String footerCredit,
        String footerCreditUrl) {

    public EmailBrandProperties {
        if (brandName == null || brandName.isBlank()) {
            brandName = "Shifa Herbal Remedies";
        }
        if (tagline == null || tagline.isBlank()) {
            tagline = "Pure herbal wellness, delivered.";
        }
        if (siteUrl == null || siteUrl.isBlank()) {
            siteUrl = "https://www.shifaherbals.com";
        }
        if (logoUrl == null) {
            logoUrl = "";
        }
        if (supportEmail == null || supportEmail.isBlank()) {
            supportEmail = "support@shifaherbals.com";
        }
        if (supportPhone == null || supportPhone.isBlank()) {
            supportPhone = "+91 93025 90767";
        }
        if (primaryColor == null || primaryColor.isBlank()) {
            primaryColor = "#1b5e20";
        }
        if (accentColor == null || accentColor.isBlank()) {
            accentColor = "#c8a44d";
        }
        if (footerCredit == null || footerCredit.isBlank()) {
            footerCredit = "Designed & Developed by Weblithic";
        }
        if (footerCreditUrl == null || footerCreditUrl.isBlank()) {
            footerCreditUrl = "https://www.weblithic.com/";
        }
    }

    /** Whether a logo image URL is configured (else the wordmark is used). */
    public boolean hasLogo() {
        return logoUrl != null && !logoUrl.isBlank();
    }

    /** Convenience factory building an instance with all on-brand defaults. */
    public static EmailBrandProperties defaults() {
        return new EmailBrandProperties(null, null, null, null, null, null, null, null, null, null);
    }
}
