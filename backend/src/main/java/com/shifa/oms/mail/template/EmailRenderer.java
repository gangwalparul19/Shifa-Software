package com.shifa.oms.mail.template;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders branded, email-client-safe HTML (plus a plain-text fallback) for every
 * customer/admin email in the system (Part 2).
 *
 * <p><strong>Pure by design.</strong> Although it is a Spring {@link Component},
 * every {@code render*} method is a pure function of its argument and the
 * injected {@link EmailBrandProperties}: no database, no request scope, no
 * clock. This lets tests construct {@code new EmailRenderer(brand)} directly and
 * assert on the returned {@link RenderedEmail} with no Spring context or mocks.
 *
 * <p><strong>Email-client-safe layout.</strong> Gmail and most webmail clients
 * strip {@code <head>} styles and much CSS, so the {@link #layout} helper uses a
 * table-based structure with <em>inline</em> styles only. The document is a
 * centered ~600px white card on a light background with a coloured brand header,
 * an optional gold CTA button, and a footer carrying support details and the
 * Weblithic credit. All caller-supplied text is HTML-escaped via {@link #esc}.
 */
@Component
public class EmailRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final EmailBrandProperties brand;

    public EmailRenderer(EmailBrandProperties brand) {
        this.brand = brand == null ? EmailBrandProperties.defaults() : brand;
    }

    // ---------------------------------------------------------------------
    // Public render methods (one per model)
    // ---------------------------------------------------------------------

    /** Order-confirmation email: warm thank-you, order code, total, "what's next", track CTA. */
    public RenderedEmail renderOrderConfirmation(EmailModels.OrderConfirmation m) {
        String subject = "Order confirmed — " + safe(m.orderCode()) + " \uD83C\uDF3F";
        String greetName = greeting(m.customerName());
        String ctaUrl = ordersUrl();

        StringBuilder body = new StringBuilder();
        body.append(p("Hi " + esc(greetName) + ", thank you for your order! \uD83C\uDF3F We're delighted to be "
                + "part of your wellness journey and are already preparing your herbal goodness with care."));
        body.append(orderCodeCard(m.orderCode(), m.orderTotal()));
        body.append(p("<strong>What's next?</strong> We'll pack your order and hand it to our courier "
                + "partner shortly. You'll get another note the moment it ships, with live tracking."));
        String html = layout(
                "Your Shifa order " + safe(m.orderCode()) + " is confirmed.",
                "Your order is confirmed \uD83C\uDF3F",
                body.toString(),
                "Track your order",
                ctaUrl);

        StringBuilder text = new StringBuilder();
        text.append("Hi ").append(greetName).append(",\n\n");
        text.append("Thank you for your order! We're preparing it with care.\n\n");
        text.append("Order: ").append(safe(m.orderCode())).append("\n");
        if (m.orderTotal() != null) {
            text.append("Order total: ").append(money(m.orderTotal())).append("\n");
        }
        text.append("\nWhat's next: we'll ship it shortly and send you tracking details.\n");
        text.append("\nTrack your order: ").append(ctaUrl).append("\n");
        text.append(textFooter());
        return new RenderedEmail(subject, html, text.toString());
    }

    /** Order-shipped email: courier + AWB, ETA, COD-to-keep-ready, track CTA. */
    public RenderedEmail renderOrderShipped(EmailModels.OrderShipped m) {
        String subject = "Your order " + safe(m.orderCode()) + " has shipped \uD83D\uDE9A";
        String greetName = greeting(m.customerName());
        String trackingUrl = (m.trackingUrl() != null && !m.trackingUrl().isBlank())
                ? m.trackingUrl() : brand.siteUrl();

        StringBuilder body = new StringBuilder();
        body.append(p("Good news, " + esc(greetName) + "! \uD83D\uDE9A Your order <strong>"
                + esc(safe(m.orderCode())) + "</strong> is on its way to you."));

        StringBuilder rows = new StringBuilder();
        rows.append(detailRow("Courier", safe(m.courierName())));
        rows.append(detailRow("Tracking no. (AWB)", safe(m.awb())));
        if (m.estimatedDelivery() != null) {
            rows.append(detailRow("Estimated delivery", m.estimatedDelivery().format(DATE)));
        }
        boolean hasCod = m.codAmount() != null && m.codAmount().compareTo(BigDecimal.ZERO) > 0;
        if (hasCod) {
            rows.append(detailRow("Amount to keep ready (COD)", money(m.codAmount())));
        }
        body.append(detailTable(rows.toString()));
        if (hasCod) {
            body.append(p("Please keep <strong>" + esc(money(m.codAmount()))
                    + "</strong> ready for the delivery partner."));
        }
        body.append(p("Tap the button below to follow your parcel in real time."));

        String html = layout(
                "Your Shifa order " + safe(m.orderCode()) + " has shipped.",
                "On its way! \uD83D\uDE9A",
                body.toString(),
                "Track shipment",
                trackingUrl);

        StringBuilder text = new StringBuilder();
        text.append("Hi ").append(greetName).append(",\n\n");
        text.append("Your order ").append(safe(m.orderCode())).append(" has shipped!\n\n");
        text.append("Courier: ").append(safe(m.courierName())).append("\n");
        text.append("Tracking no. (AWB): ").append(safe(m.awb())).append("\n");
        if (m.estimatedDelivery() != null) {
            text.append("Estimated delivery: ").append(m.estimatedDelivery().format(DATE)).append("\n");
        }
        if (hasCod) {
            text.append("Amount to keep ready (COD): ").append(money(m.codAmount())).append("\n");
        }
        text.append("\nTrack shipment: ").append(trackingUrl).append("\n");
        text.append(textFooter());
        return new RenderedEmail(subject, html, text.toString());
    }

    /** Order-delivered email: thank-you + review nudge, review CTA. */
    public RenderedEmail renderOrderDelivered(EmailModels.OrderDelivered m) {
        String subject = "Delivered! How did we do? \uD83C\uDF3F";
        String greetName = greeting(m.customerName());
        String ctaUrl = brand.siteUrl();

        StringBuilder body = new StringBuilder();
        body.append(p("Hi " + esc(greetName) + ", your order <strong>" + esc(safe(m.orderCode()))
                + "</strong> has been delivered. We hope you love it! \uD83C\uDF3F"));
        body.append(p("Your feedback helps other wellness seekers and helps us keep improving. "
                + "Would you take a moment to share how we did?"));

        String html = layout(
                "Your Shifa order " + safe(m.orderCode()) + " has been delivered.",
                "Delivered \uD83C\uDF3F",
                body.toString(),
                "Write a review",
                ctaUrl);

        StringBuilder text = new StringBuilder();
        text.append("Hi ").append(greetName).append(",\n\n");
        text.append("Your order ").append(safe(m.orderCode())).append(" has been delivered. ");
        text.append("We hope you love it!\n\n");
        text.append("We'd love your feedback — write a review: ").append(ctaUrl).append("\n");
        text.append(textFooter());
        return new RenderedEmail(subject, html, text.toString());
    }

    /** Welcome email: warm welcome, value props, shop CTA. */
    public RenderedEmail renderWelcome(EmailModels.Welcome m) {
        String subject = "Welcome to " + brand.brandName() + " \uD83C\uDF3F";
        String greetName = greeting(m.customerName());
        String ctaUrl = brand.siteUrl();

        StringBuilder body = new StringBuilder();
        body.append(p("Welcome, " + esc(greetName) + "! \uD83C\uDF3F We're thrilled to have you at <strong>"
                + esc(brand.brandName()) + "</strong>. " + esc(brand.tagline())));
        body.append("<ul style=\"margin:0 0 16px 0;padding-left:20px;color:#3a3a3a;"
                + "font-size:15px;line-height:1.6;\">");
        body.append("<li>Carefully sourced, authentic herbal remedies</li>");
        body.append("<li>Fast, tracked delivery to your door</li>");
        body.append("<li>Friendly support whenever you need it</li>");
        body.append("</ul>");
        body.append(p("Ready to begin? Explore our collection and find what your wellness routine "
                + "has been missing."));

        String html = layout(
                "Welcome to " + brand.brandName() + " \u2014 pure herbal wellness.",
                "Welcome to the family \uD83C\uDF3F",
                body.toString(),
                "Start shopping",
                ctaUrl);

        StringBuilder text = new StringBuilder();
        text.append("Welcome, ").append(greetName).append("!\n\n");
        text.append("We're thrilled to have you at ").append(brand.brandName()).append(". ")
                .append(brand.tagline()).append("\n\n");
        text.append("- Carefully sourced, authentic herbal remedies\n");
        text.append("- Fast, tracked delivery to your door\n");
        text.append("- Friendly support whenever you need it\n\n");
        text.append("Start shopping: ").append(ctaUrl).append("\n");
        text.append(textFooter());
        return new RenderedEmail(subject, html, text.toString());
    }

    /** Daily digest email (internal): a clean metrics table, no CTA. */
    public RenderedEmail renderDigest(EmailModels.Digest m) {
        String dayLabel = m.day() == null ? "" : m.day().format(DAY_ISO);
        String subject = "Shifa daily digest — " + dayLabel;

        StringBuilder rows = new StringBuilder();
        rows.append(detailRow("Date", dayLabel));
        rows.append(detailRow("Orders", String.valueOf(m.orderCount())));
        rows.append(detailRow("Total sales", money(m.totalSales())));
        rows.append(detailRow("Prepaid", m.prepaidCount() + " order(s) · " + money(m.prepaidSales())));
        rows.append(detailRow("COD", m.codCount() + " order(s) · " + money(m.codSales())));
        rows.append(detailRow("Excluded (rejected/cancelled)", String.valueOf(m.excludedCount())));

        StringBuilder body = new StringBuilder();
        if (m.orderCount() == 0) {
            body.append(p("No qualifying orders for " + esc(dayLabel) + "."));
        } else {
            body.append(p("Here's how " + esc(dayLabel) + " looked across the store."));
        }
        body.append(detailTable(rows.toString()));

        String html = layout(
                "Shifa daily sales digest for " + dayLabel + ".",
                "Daily sales digest",
                body.toString(),
                null,
                null);

        StringBuilder text = new StringBuilder();
        text.append("Shifa Herbal Remedies — daily sales digest\n");
        text.append("Date: ").append(dayLabel).append("\n\n");
        text.append("Orders: ").append(m.orderCount()).append("\n");
        text.append("Total sales: ").append(money(m.totalSales())).append("\n");
        text.append("  Prepaid: ").append(m.prepaidCount()).append(" order(s), ")
                .append(money(m.prepaidSales())).append("\n");
        text.append("  COD: ").append(m.codCount()).append(" order(s), ")
                .append(money(m.codSales())).append("\n");
        text.append("Excluded (rejected/cancelled): ").append(m.excludedCount()).append("\n");
        return new RenderedEmail(subject, html, text.toString());
    }

    // ---------------------------------------------------------------------
    // Layout + HTML fragment helpers
    // ---------------------------------------------------------------------

    /**
     * Produces the full, email-client-safe HTML document (table layout + inline
     * styles). The CTA block is omitted when {@code ctaLabel}/{@code ctaUrl} are
     * blank (e.g. the internal digest).
     */
    private String layout(String preheader, String heading, String bodyHtml,
                          String ctaLabel, String ctaUrl) {
        String primary = brand.primaryColor();
        String accent = brand.accentColor();

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head>");
        sb.append("<meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        sb.append("<title>").append(esc(brand.brandName())).append("</title>");
        sb.append("</head>");
        sb.append("<body style=\"margin:0;padding:0;background-color:#f4f6f4;"
                + "font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;\">");

        // Hidden preheader (preview text).
        sb.append("<span style=\"display:none !important;visibility:hidden;opacity:0;"
                + "color:transparent;height:0;width:0;overflow:hidden;mso-hide:all;\">")
                .append(esc(preheader)).append("</span>");

        // Outer full-width table centering the 600px card.
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"background-color:#f4f6f4;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">");
        sb.append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"width:600px;max-width:600px;background-color:#ffffff;border-radius:10px;"
                + "overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,0.06);\">");

        // Brand header bar.
        sb.append("<tr><td style=\"background-color:").append(primary)
                .append(";padding:24px 32px;text-align:center;\">");
        sb.append(headerBrand());
        sb.append("</td></tr>");

        // Content card body.
        sb.append("<tr><td style=\"padding:32px;\">");
        sb.append("<h1 style=\"margin:0 0 16px 0;font-size:22px;line-height:1.3;color:")
                .append(primary).append(";\">").append(esc(heading)).append("</h1>");
        sb.append(bodyHtml);

        // Optional CTA button (rounded, gold).
        if (ctaLabel != null && !ctaLabel.isBlank() && ctaUrl != null && !ctaUrl.isBlank()) {
            sb.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
                    + "style=\"margin:24px 0 8px 0;\"><tr><td align=\"center\" "
                    + "style=\"border-radius:6px;background-color:").append(accent).append(";\">");
            sb.append("<a href=\"").append(attr(ctaUrl)).append("\" target=\"_blank\" "
                    + "style=\"display:inline-block;padding:13px 28px;font-size:16px;font-weight:bold;"
                    + "color:#ffffff;text-decoration:none;border-radius:6px;background-color:")
                    .append(accent).append(";\">").append(esc(ctaLabel)).append("</a>");
            sb.append("</td></tr></table>");
        }

        sb.append("</td></tr>");

        // Footer.
        sb.append("<tr><td style=\"background-color:#f0f2ef;padding:24px 32px;border-top:1px solid #e2e6e1;\">");
        sb.append(footer());
        sb.append("</td></tr>");

        sb.append("</table></td></tr></table>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** The brand header content: a logo image when configured, else a styled wordmark. */
    private String headerBrand() {
        StringBuilder sb = new StringBuilder();
        if (brand.hasLogo()) {
            sb.append("<img src=\"").append(attr(brand.logoUrl())).append("\" alt=\"")
                    .append(attr(brand.brandName())).append("\" width=\"180\" "
                    + "style=\"display:inline-block;max-width:180px;height:auto;border:0;\">");
        } else {
            sb.append("<div style=\"font-size:24px;font-weight:bold;color:#ffffff;letter-spacing:0.3px;\">")
                    .append("\uD83C\uDF3F ").append(esc(brand.brandName())).append("</div>");
        }
        sb.append("<div style=\"margin-top:6px;font-size:13px;color:").append(brand.accentColor())
                .append(";\">").append(esc(brand.tagline())).append("</div>");
        return sb.toString();
    }

    /** The footer: brand, site link, support details, and the Weblithic credit. */
    private String footer() {
        StringBuilder sb = new StringBuilder();
        sb.append("<p style=\"margin:0 0 6px 0;font-size:13px;color:#5a5f57;line-height:1.5;\">");
        sb.append("<strong>").append(esc(brand.brandName())).append("</strong><br>");
        sb.append("<a href=\"").append(attr(brand.siteUrl())).append("\" target=\"_blank\" "
                + "style=\"color:").append(brand.primaryColor()).append(";text-decoration:none;\">")
                .append(esc(brand.siteUrl())).append("</a>");
        sb.append("</p>");
        sb.append("<p style=\"margin:0 0 6px 0;font-size:12px;color:#7a7f76;line-height:1.5;\">");
        sb.append("Need help? ");
        sb.append("<a href=\"mailto:").append(attr(brand.supportEmail())).append("\" "
                + "style=\"color:").append(brand.primaryColor()).append(";text-decoration:none;\">")
                .append(esc(brand.supportEmail())).append("</a>");
        sb.append(" &bull; ").append(esc(brand.supportPhone()));
        sb.append("</p>");
        sb.append("<p style=\"margin:8px 0 0 0;font-size:11px;color:#9aa093;line-height:1.5;\">");
        sb.append("<a href=\"").append(attr(brand.footerCreditUrl())).append("\" target=\"_blank\" "
                + "style=\"color:#9aa093;text-decoration:underline;\">")
                .append(esc(brand.footerCredit())).append("</a>");
        sb.append("</p>");
        return sb.toString();
    }

    /** A body paragraph. */
    private String p(String innerHtml) {
        return "<p style=\"margin:0 0 16px 0;font-size:15px;line-height:1.6;color:#3a3a3a;\">"
                + innerHtml + "</p>";
    }

    /** A highlighted card showing the order code and (optional) total. */
    private String orderCodeCard(String orderCode, BigDecimal total) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:0 0 16px 0;background-color:#f4f6f4;border-radius:8px;\">");
        sb.append("<tr><td style=\"padding:16px 20px;\">");
        sb.append("<div style=\"font-size:13px;color:#7a7f76;\">Order</div>");
        sb.append("<div style=\"font-size:18px;font-weight:bold;color:").append(brand.primaryColor())
                .append(";\">").append(esc(safe(orderCode))).append("</div>");
        if (total != null) {
            sb.append("<div style=\"margin-top:8px;font-size:13px;color:#7a7f76;\">Order total</div>");
            sb.append("<div style=\"font-size:16px;font-weight:bold;color:#3a3a3a;\">")
                    .append(esc(money(total))).append("</div>");
        }
        sb.append("</td></tr></table>");
        return sb.toString();
    }

    /** Wraps detail rows in a bordered table. */
    private String detailTable(String rowsHtml) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:0 0 16px 0;border:1px solid #e2e6e1;border-radius:8px;"
                + "border-collapse:separate;\">" + rowsHtml + "</table>";
    }

    /** A single label/value detail row. */
    private String detailRow(String label, String value) {
        return "<tr>"
                + "<td style=\"padding:10px 16px;font-size:13px;color:#7a7f76;"
                + "border-bottom:1px solid #eef0ec;width:45%;\">" + esc(label) + "</td>"
                + "<td style=\"padding:10px 16px;font-size:14px;color:#3a3a3a;font-weight:bold;"
                + "border-bottom:1px solid #eef0ec;\">" + esc(value) + "</td>"
                + "</tr>";
    }

    /** The plain-text footer block appended to every customer text fallback. */
    private String textFooter() {
        return "\n--\n" + brand.brandName() + "\n"
                + brand.siteUrl() + "\n"
                + "Support: " + brand.supportEmail() + " | " + brand.supportPhone() + "\n"
                + brand.footerCredit() + " (" + brand.footerCreditUrl() + ")\n";
    }

    // ---------------------------------------------------------------------
    // Small formatting utilities
    // ---------------------------------------------------------------------

    /** The storefront orders URL used by the confirmation CTA. */
    private String ordersUrl() {
        String base = brand.siteUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        return base.endsWith("/") ? base + "orders" : base + "/orders";
    }

    /** The greeting name: the customer's name when non-blank, else a friendly fallback. */
    private static String greeting(String name) {
        return (name == null || name.isBlank()) ? "there" : name.trim();
    }

    /** Null-safe string ("" for null). */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Formats an amount as grouped rupees with 2 decimals (e.g. {@code ₹1,299.00}). */
    private static String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        DecimalFormat df = new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.ENGLISH));
        return "\u20B9" + df.format(value.setScale(2, RoundingMode.HALF_UP));
    }

    /** HTML-escapes text content for {@code & < > "}. */
    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Escapes a value for use inside a double-quoted HTML attribute (href/src). */
    private static String attr(String value) {
        return esc(value);
    }
}
