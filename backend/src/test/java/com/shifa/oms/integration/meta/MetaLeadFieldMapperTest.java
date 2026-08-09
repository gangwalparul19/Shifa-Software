package com.shifa.oms.integration.meta;

import com.shifa.oms.integration.meta.dto.MetaField;
import com.shifa.oms.lead.dto.CreateLeadRequest;
import com.shifa.oms.order.LeadSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MetaLeadFieldMapper} (spec {@code meta-lead-sync}, Req 6, 7.3, 7.4).
 */
class MetaLeadFieldMapperTest {

    @Test
    void mapsStandardFieldsAndNormalisesPhone() {
        CreateLeadRequest req = MetaLeadFieldMapper.map(List.of(
                new MetaField("full_name", "Asha Rao"),
                new MetaField("email", "asha@example.com"),
                new MetaField("phone_number", "+91 98123 45678")), "Diwali Campaign");

        assertThat(req.customerName()).isEqualTo("Asha Rao");
        assertThat(req.customerEmail()).isEqualTo("asha@example.com");
        assertThat(req.customerMobile()).isEqualTo("9812345678");
        assertThat(req.leadSource()).isEqualTo(LeadSource.FACEBOOK);
        assertThat(req.leadSourceNote()).isEqualTo("Diwali Campaign");
    }

    @Test
    void buildsFullNameFromFirstAndLast() {
        CreateLeadRequest req = MetaLeadFieldMapper.map(List.of(
                new MetaField("first_name", "Asha"),
                new MetaField("last_name", "Rao")), null);
        assertThat(req.customerName()).isEqualTo("Asha Rao");
    }

    @Test
    void usesPlaceholderWhenNameMissing() {
        CreateLeadRequest req = MetaLeadFieldMapper.map(List.of(
                new MetaField("email", "x@example.com")), null);
        assertThat(req.customerName()).isEqualTo(MetaLeadFieldMapper.PLACEHOLDER_NAME);
    }

    @Test
    void keepsOriginalPhoneInNoteWhenNotNormalisable() {
        CreateLeadRequest req = MetaLeadFieldMapper.map(List.of(
                new MetaField("full_name", "Asha"),
                new MetaField("phone_number", "12345")), null);
        assertThat(req.customerMobile()).isNull();
        assertThat(req.note()).contains("12345");
    }

    @Test
    void recordsCustomQuestionsInNote() {
        CreateLeadRequest req = MetaLeadFieldMapper.map(List.of(
                new MetaField("full_name", "Asha"),
                new MetaField("which_product", "Ashwagandha"),
                new MetaField("city", "Pune")), null);
        assertThat(req.note()).contains("Which product: Ashwagandha");
        assertThat(req.note()).contains("City: Pune");
    }

    @Test
    void clampsSourceNoteTo200Chars() {
        String longForm = "F".repeat(250);
        CreateLeadRequest req = MetaLeadFieldMapper.map(List.of(
                new MetaField("full_name", "Asha")), longForm);
        assertThat(req.leadSourceNote()).hasSize(200);
    }

    @Test
    void nullNoteWhenNoCustomFieldsAndPhoneMapped() {
        CreateLeadRequest req = MetaLeadFieldMapper.map(List.of(
                new MetaField("full_name", "Asha"),
                new MetaField("phone_number", "9812345678")), "Form");
        assertThat(req.note()).isNull();
    }
}
