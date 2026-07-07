package com.shifa.oms.mail.template;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EmailRenderer} (Part 2). The renderer is a pure function
 * of its argument + a fixed {@link EmailBrandProperties}, so every test
 * constructs it directly with no Spring context or mocks and asserts on the
 * returned {@link RenderedEmail} (subject, HTML content, and text fallback).
 */
class EmailRendererTest {

    private static final EmailBrandProperties BRAND = new EmailBrandProperties(
            "Shifa Herbal Remedies",
            "Pure herbal wellness, delivered.",
            "https://shop.shifa.test",
            "",
            "care@shifa.test",
            "+91 90000 00000",
            "#1b5e20",
            "#c8a44d",
            "Designed & Developed by Weblithic",
            "https://www.weblithic.com/");

    private final EmailRenderer renderer = new EmailRenderer(BRAND);

    @Test
    void orderConfirmationRendersBrandedHtmlAndText() {
        RenderedEmail email = renderer.renderOrderConfirmation(
                new EmailModels.OrderConfirmation("Aisha", "SHR-1042", new BigDecimal("1299.00")));

        assertThat(email.subject()).isEqualTo("Order confirmed — SHR-1042 \uD83C\uDF3F");
        assertThat(email.html()).isNotBlank();
        assertThat(email.html()).contains("Shifa Herbal Remedies");
        assertThat(email.html()).contains("SHR-1042");
        assertThat(email.html()).contains("Aisha");
        assertThat(email.html()).contains("1,299.00");
        assertThat(email.html()).contains("Track your order");
        // Weblithic footer credit + URL present.
        assertThat(email.html()).contains("Designed &amp; Developed by Weblithic");
        assertThat(email.html()).contains("https://www.weblithic.com/");
        // Text fallback: order code + spelled-out link.
        assertThat(email.text()).contains("SHR-1042");
        assertThat(email.text()).contains("https://shop.shifa.test/orders");
    }

    @Test
    void orderConfirmationHidesTotalWhenNull() {
        RenderedEmail email = renderer.renderOrderConfirmation(
                new EmailModels.OrderConfirmation("Aisha", "SHR-2000", null));

        assertThat(email.html()).contains("SHR-2000");
        assertThat(email.text()).doesNotContain("Order total");
    }

    @Test
    void orderConfirmationFallsBackToFriendlyGreetingWhenNameBlank() {
        RenderedEmail email = renderer.renderOrderConfirmation(
                new EmailModels.OrderConfirmation("  ", "SHR-3000", new BigDecimal("50.00")));

        assertThat(email.html()).contains("Hi there,");
        assertThat(email.text()).contains("Hi there,");
    }

    @Test
    void orderShippedRendersTrackingCourierAndCod() {
        RenderedEmail email = renderer.renderOrderShipped(new EmailModels.OrderShipped(
                "Bilal", "SHR-1055", "BlueDart", "AWB99887766",
                "https://track.test/AWB99887766", LocalDate.of(2024, 6, 12),
                new BigDecimal("899.50")));

        assertThat(email.subject()).isEqualTo("Your order SHR-1055 has shipped \uD83D\uDE9A");
        assertThat(email.html()).contains("BlueDart");
        assertThat(email.html()).contains("AWB99887766");
        assertThat(email.html()).contains("12 Jun 2024");
        assertThat(email.html()).contains("899.50");
        assertThat(email.html()).contains("Track shipment");
        assertThat(email.html()).contains("https://track.test/AWB99887766");
        assertThat(email.html()).contains("Designed &amp; Developed by Weblithic");
        assertThat(email.text()).contains("AWB99887766");
        assertThat(email.text()).contains("https://track.test/AWB99887766");
    }

    @Test
    void orderShippedHidesCodWhenNullOrZeroAndFallsBackToSiteUrl() {
        RenderedEmail email = renderer.renderOrderShipped(new EmailModels.OrderShipped(
                "Bilal", "SHR-1056", "BlueDart", "AWB1", null, null, BigDecimal.ZERO));

        assertThat(email.html()).doesNotContain("keep ready");
        // Blank tracking url -> CTA falls back to the site url.
        assertThat(email.html()).contains("https://shop.shifa.test");
        assertThat(email.text()).doesNotContain("Estimated delivery");
    }

    @Test
    void orderDeliveredRendersReviewNudge() {
        RenderedEmail email = renderer.renderOrderDelivered(
                new EmailModels.OrderDelivered("Carol", "SHR-1099"));

        assertThat(email.subject()).isEqualTo("Delivered! How did we do? \uD83C\uDF3F");
        assertThat(email.html()).contains("SHR-1099");
        assertThat(email.html()).contains("Write a review");
        assertThat(email.html()).contains("Shifa Herbal Remedies");
        assertThat(email.html()).contains("https://www.weblithic.com/");
        assertThat(email.text()).contains("SHR-1099");
        assertThat(email.text()).contains("https://shop.shifa.test");
    }

    @Test
    void welcomeRendersValuePropsAndCta() {
        RenderedEmail email = renderer.renderWelcome(new EmailModels.Welcome("Deepa"));

        assertThat(email.subject()).isEqualTo("Welcome to Shifa Herbal Remedies \uD83C\uDF3F");
        assertThat(email.html()).contains("Deepa");
        assertThat(email.html()).contains("Start shopping");
        assertThat(email.html()).contains("Shifa Herbal Remedies");
        assertThat(email.html()).contains("Designed &amp; Developed by Weblithic");
        assertThat(email.text()).contains("Start shopping: https://shop.shifa.test");
    }

    @Test
    void digestRendersMetricsTableWithNoCta() {
        RenderedEmail email = renderer.renderDigest(new EmailModels.Digest(
                LocalDate.of(2024, 5, 10), 3, new BigDecimal("400.00"),
                2, new BigDecimal("150.00"), 1, new BigDecimal("250.00"), 2));

        assertThat(email.subject()).isEqualTo("Shifa daily digest — 2024-05-10");
        assertThat(email.html()).isNotBlank();
        assertThat(email.html()).contains("2024-05-10");
        assertThat(email.html()).contains("400.00");
        assertThat(email.html()).contains("Shifa Herbal Remedies");
        assertThat(email.html()).contains("https://www.weblithic.com/");
        // Internal email: no CTA button label.
        assertThat(email.html()).doesNotContain("Track your order");
        assertThat(email.text()).contains("Total sales: \u20B9400.00");
        assertThat(email.text()).contains("Excluded (rejected/cancelled): 2");
    }

    @Test
    void escapesHtmlSpecialCharactersInUserSuppliedText() {
        RenderedEmail email = renderer.renderOrderConfirmation(
                new EmailModels.OrderConfirmation("<b>Eve</b> & \"Co\"", "SHR-1<2", null));

        assertThat(email.html()).contains("&lt;b&gt;Eve&lt;/b&gt; &amp; &quot;Co&quot;");
        assertThat(email.html()).doesNotContain("<b>Eve</b>");
        assertThat(email.html()).contains("SHR-1&lt;2");
    }

    @Test
    void toMessageBuildsMultipartMailMessage() {
        RenderedEmail email = renderer.renderWelcome(new EmailModels.Welcome("Farah"));

        var message = email.toMessage("farah@example.com");
        assertThat(message.to()).isEqualTo("farah@example.com");
        assertThat(message.subject()).isEqualTo(email.subject());
        assertThat(message.hasHtml()).isTrue();
        assertThat(message.htmlBody()).isEqualTo(email.html());
        assertThat(message.body()).isEqualTo(email.text());
    }
}
