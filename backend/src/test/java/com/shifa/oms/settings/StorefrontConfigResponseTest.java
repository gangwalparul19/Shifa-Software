package com.shifa.oms.settings;

import com.shifa.oms.settings.dto.StorefrontConfigResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the public {@link StorefrontConfigResponse} projection:
 * <ul>
 *   <li>it copies the store name, contact phone (WhatsApp), and support email;</li>
 *   <li>it falls back to the brand name when the legal name is blank;</li>
 *   <li>it never exposes GST/legal internals (only the three public fields).</li>
 * </ul>
 */
class StorefrontConfigResponseTest {

    @Test
    void projectsPublicSafeDisplayFields() {
        AppSettings settings = new AppSettings();
        settings.setLegalName("Shifa Herbal Pvt Ltd");
        settings.setContactPhone("+91 90000 00000");
        settings.setContactEmail("care@shifaherbal.example");
        settings.setGstin("23ABCDE1234F1Z5");

        StorefrontConfigResponse response = StorefrontConfigResponse.from(settings);

        assertThat(response.storeName()).isEqualTo("Shifa Herbal Pvt Ltd");
        assertThat(response.whatsappNumber()).isEqualTo("+91 90000 00000");
        assertThat(response.supportEmail()).isEqualTo("care@shifaherbal.example");
    }

    @Test
    void fallsBackToBrandNameWhenLegalNameBlank() {
        AppSettings settings = new AppSettings();
        settings.setLegalName("   ");

        StorefrontConfigResponse response = StorefrontConfigResponse.from(settings);

        assertThat(response.storeName()).isEqualTo("Shifa Herbal Remedies");
    }
}
