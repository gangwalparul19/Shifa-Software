package com.shifa.oms.mail.template;

import com.shifa.oms.incentive.domain.IncentiveFormat;
import com.shifa.oms.mail.report.MorningBrief;
import com.shifa.oms.mail.report.MorningBrief.Attention;
import com.shifa.oms.mail.report.MorningBrief.BankRow;
import com.shifa.oms.mail.report.MorningBrief.Banks;
import com.shifa.oms.mail.report.MorningBrief.CashOnDelivery;
import com.shifa.oms.mail.report.MorningBrief.ChannelRow;
import com.shifa.oms.mail.report.MorningBrief.Comparison;
import com.shifa.oms.mail.report.MorningBrief.Dues;
import com.shifa.oms.mail.report.MorningBrief.Earner;
import com.shifa.oms.mail.report.MorningBrief.Highlights;
import com.shifa.oms.mail.report.MorningBrief.Incentive;
import com.shifa.oms.mail.report.MorningBrief.PersonRow;
import com.shifa.oms.mail.report.MorningBrief.ProductRow;
import com.shifa.oms.mail.report.MorningBrief.Slab;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Turns a {@link MorningBrief} into the HTML body, the plain-text version, the subject and the
 * preview line of the admin's morning email. Pure: no database, no clock.
 *
 * <p>Email clients ignore most CSS, so everything is nested tables with inline styles. The card is
 * 640px wide on a desktop and shrinks to the screen on a phone. Amounts use Indian digit grouping
 * ({@code ₹1,23,456}) like the rest of the app.
 */
final class MorningBriefRenderer {

    /** Card width in pixels; {@link EmailRenderer#renderMorningBrief} passes it to the layout. */
    static final int CARD_WIDTH = 640;

    private static final String INK = "#2b2f2a";
    private static final String MUTED = "#7a7f76";
    private static final String LINE = "#eef0ec";
    private static final String SOFT = "#f4f6f4";
    private static final String GOOD = "#2e7d32";
    private static final String BAD = "#c62828";
    private static final String[] SOURCE_COLORS = {"#1b5e20", "#c8a44d", "#8d9889"};
    private static final String[] MEDALS = {"#c8a44d", "#9aa0a6", "#a8703a"};

    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter RANGE_END = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EmailBrandProperties brand;

    MorningBriefRenderer(EmailBrandProperties brand) {
        this.brand = brand == null ? EmailBrandProperties.defaults() : brand;
    }

    /** "yesterday", or "on Fri 31 Oct" when the report was run for some other day. */
    private static String when(MorningBrief b) {
        return b.yesterday() ? "yesterday" : "on " + SHORT_DAY.format(b.day());
    }

    // ---------------------------------------------------------------------------------------------
    // Subject and preview
    // ---------------------------------------------------------------------------------------------

    String subject(MorningBrief b) {
        long orders = b.headline().orders();
        String result = orders == 0
                ? "no orders"
                : inr(b.headline().sales()) + " from " + plural(orders, "order");
        return "Shifa morning brief \u00B7 " + SHORT_DAY.format(b.day()) + " \u2014 " + result;
    }

    String preheader(MorningBrief b) {
        StringBuilder sb = new StringBuilder();
        sb.append(inr(b.headline().sales())).append(" sales from ").append(plural(b.headline().orders(), "order"));
        if (b.topSalespeople() != null && !b.topSalespeople().isEmpty()) {
            sb.append(" \u00B7 Top: ").append(b.topSalespeople().get(0).name());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // HTML body
    // ---------------------------------------------------------------------------------------------

    String body(MorningBrief b) {
        StringBuilder sb = new StringBuilder(12_000);
        sb.append(hero(b));
        sb.append(kpiRow(b));
        sb.append(sourcesSection(b));
        sb.append(banksSection(b));
        sb.append(codSection(b));
        sb.append(topSection(b));
        sb.append(incentiveSection(b));
        sb.append(highlightsSection(b));
        sb.append(attentionSection(b));
        sb.append(footnote());
        return sb.toString();
    }

    private String hero(MorningBrief b) {
        MorningBrief.Headline h = b.headline();
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:0 0 14px 0;background-color:").append(brand.primaryColor())
                .append(";border-radius:10px;\"><tr><td style=\"padding:20px 22px;\">");
        sb.append("<div style=\"font-size:11px;letter-spacing:1px;text-transform:uppercase;font-weight:bold;"
                + "color:").append(brand.accentColor()).append(";\">")
                .append(b.yesterday() ? "Yesterday" : "Report for").append(" \u00B7 ")
                .append(esc(LONG_DAY.format(b.day()))).append("</div>");
        sb.append("<div style=\"margin-top:8px;font-size:34px;line-height:1.1;font-weight:bold;color:#ffffff;\">")
                .append(esc(inr(h.sales()))).append("</div>");
        if (h.orders() == 0) {
            sb.append("<div style=\"margin-top:4px;font-size:14px;color:#d8e6d8;\">")
                    .append(esc("No orders were placed " + when(b) + ".")).append("</div>");
        } else {
            sb.append("<div style=\"margin-top:4px;font-size:14px;color:#d8e6d8;\">total sales from <strong>")
                    .append(esc(plural(h.orders(), "order"))).append("</strong> \u00B7 average ")
                    .append(esc(inr(h.averageOrderValue()))).append(" per order</div>");
        }
        sb.append("<div style=\"margin-top:12px;font-size:13px;line-height:1.7;\">");
        sb.append(delta("the day before", h.sales(), h.previousDay()));
        sb.append(delta("the same day last week", h.sales(), h.sameWeekdayLastWeek()));
        sb.append("</div></td></tr></table>");
        return sb.toString();
    }

    /** One comparison line on the dark hero card. */
    private String delta(String what, BigDecimal now, Comparison c) {
        String when = SHORT_DAY.format(c.day());
        BigDecimal pct = MorningBrief.changePercent(now, c.sales());
        if (pct == null) {
            return line("#d8e6d8", "\u2022", "No sales on " + when + " (" + what + ") to compare with.");
        }
        String text = percent(pct.abs()) + " " + (pct.signum() >= 0 ? "above" : "below") + " " + what
                + " (" + when + ": " + inr(c.sales()) + ")";
        if (pct.signum() > 0) {
            return line("#a5d6a7", "\u25B2", text);
        }
        if (pct.signum() < 0) {
            return line("#ffab91", "\u25BC", text);
        }
        return line("#d8e6d8", "\u2022", "Same as " + what + " (" + when + ": " + inr(c.sales()) + ")");
    }

    private static String line(String color, String arrow, String text) {
        return "<div style=\"color:" + color + ";\">" + arrow + " " + esc(text) + "</div>";
    }

    private String kpiRow(MorningBrief b) {
        MorningBrief.Headline h = b.headline();
        String banks = b.banks() == null ? "\u2014" : inr(b.banks().total());
        String banksSub = b.banks() == null ? "not available" : plural(b.banks().payments(), "payment");
        String cod = b.cod() == null ? "\u2014" : inr(b.cod().toCollect());
        String codSub = b.cod() == null ? "not available" : "on " + plural(b.cod().ordersWithCod(), "order");
        String monthSub = plural(h.monthOrders(), "order") + " \u00B7 1\u2013" + RANGE_END.format(b.day());
        // Fluid tiles: three across on a desktop, one per row on a phone. A table row of three would
        // refuse to shrink below the width of three amounts and push the whole email off the screen.
        return "<div style=\"margin:0 -4px 4px -4px;font-size:0;line-height:0;\">"
                + kpiCard("Collected in banks", banks, banksSub)
                + kpiCard("To collect on delivery", cod, codSub)
                + kpiCard(MONTH.format(b.day()) + " so far", inr(h.monthSales()), monthSub)
                + "</div>";
    }

    private String kpiCard(String label, String value, String sub) {
        return "<div style=\"display:inline-block;width:33.33%;min-width:180px;max-width:100%;vertical-align:top;"
                + "font-size:13px;line-height:1.3;\"><div style=\"padding:0 4px 8px 4px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"background-color:" + SOFT + ";border-radius:8px;\"><tr><td style=\"padding:12px 12px;\">"
                + "<div style=\"font-size:10px;letter-spacing:0.6px;text-transform:uppercase;color:" + MUTED + ";\">"
                + esc(label) + "</div>"
                + "<div style=\"margin-top:4px;font-size:18px;font-weight:bold;color:" + brand.primaryColor() + ";\">"
                + esc(value) + "</div>"
                + "<div style=\"margin-top:2px;font-size:11px;color:" + MUTED + ";\">" + esc(sub) + "</div>"
                + "</td></tr></table></div></div>";
    }

    // --- Sales by source -------------------------------------------------------------------------

    private String sourcesSection(MorningBrief b) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading("Sales by source", "Portal is our own team; Shopify is the website; Store is the shop counter."));
        BigDecimal totalSales = b.total().sales();

        if (totalSales.signum() > 0) {
            sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                    + "style=\"margin:0 0 10px 0;border-radius:5px;overflow:hidden;\"><tr>");
            for (int i = 0; i < b.channels().size(); i++) {
                int share = shareOf(b.channels().get(i).sales(), totalSales);
                if (share > 0) {
                    sb.append("<td width=\"").append(share).append("%\" height=\"10\" style=\"height:10px;"
                            + "font-size:0;line-height:0;background-color:").append(SOURCE_COLORS[i % 3])
                            .append(";\">&nbsp;</td>");
                }
            }
            sb.append("</tr></table>");
        }

        // Four columns, with the order count under the source name: five would not fit a phone.
        StringBuilder rows = new StringBuilder();
        rows.append(headRow(new String[]{"Source", "Sales", "Share", "To collect on delivery"},
                new boolean[]{false, true, true, true}));
        for (int i = 0; i < b.channels().size(); i++) {
            ChannelRow c = b.channels().get(i);
            String label = "<span style=\"display:inline-block;width:9px;height:9px;border-radius:5px;"
                    + "margin-right:7px;background-color:" + SOURCE_COLORS[i % 3] + ";\"></span>" + esc(c.label())
                    + subLabel(plural(c.orders(), "order"));
            rows.append(row(new String[]{label, esc(inr(c.sales())), shareOf(c.sales(), totalSales) + "%",
                            esc(inr(c.codToCollect()))},
                    new boolean[]{false, true, true, true}, new boolean[]{true, false, false, false}, false));
        }
        ChannelRow t = b.total();
        rows.append(row(new String[]{"Total" + subLabel(plural(t.orders(), "order")), esc(inr(t.sales())),
                        totalSales.signum() > 0 ? "100%" : "\u2014", esc(inr(t.codToCollect()))},
                new boolean[]{false, true, true, true}, new boolean[]{true, true, true, true}, true));
        sb.append(grid(rows.toString()));
        return sb.toString();
    }

    /** A small grey line under a label, for example the number of orders under a source. */
    private static String subLabel(String text) {
        return "<br><span style=\"font-size:11px;font-weight:normal;color:" + MUTED + ";\">" + esc(text) + "</span>";
    }

    // --- Money in banks --------------------------------------------------------------------------

    private String banksSection(MorningBrief b) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading("Collected " + when(b) + ", by bank / QR account",
                "Money recorded on orders " + when(b) + ", in the account the salesperson chose."));
        Banks banks = b.banks();
        if (banks == null) {
            return sb.append(unavailable()).toString();
        }
        if (banks.rows().isEmpty()) {
            return sb.append(note("No payments were recorded " + when(b) + ".")).toString();
        }
        StringBuilder rows = new StringBuilder();
        rows.append(headRow(new String[]{"Account", "Payments", "Amount"}, new boolean[]{false, true, true}));
        for (BankRow r : banks.rows()) {
            rows.append(row(new String[]{esc(r.account()), String.valueOf(r.payments()), esc(inr(r.amount()))},
                    new boolean[]{false, true, true}, new boolean[]{true, false, true}, false));
        }
        if (banks.rows().size() > 1) {
            rows.append(row(new String[]{"Total", String.valueOf(banks.payments()), esc(inr(banks.total()))},
                    new boolean[]{false, true, true}, new boolean[]{true, true, true}, true));
        }
        sb.append(grid(rows.toString()));
        if (banks.awaitingVerification().signum() > 0) {
            sb.append(note(inr(banks.awaitingVerification()) + " of this is still waiting for the payment verifier."));
        }
        if (banks.rejected().signum() > 0) {
            sb.append(noteBad(inr(banks.rejected()) + " of this was rejected by the payment verifier."));
        }
        return sb.toString();
    }

    // --- Cash on delivery ------------------------------------------------------------------------

    private String codSection(MorningBrief b) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading("Cash on delivery", "Cash the courier or our delivery person collects from the customer."));
        CashOnDelivery cod = b.cod();
        if (cod == null) {
            sb.append(unavailable());
        } else {
            StringBuilder rows = new StringBuilder();
            rows.append(detailRow("To collect on delivery \u2014 orders placed " + when(b),
                    inr(cod.toCollect()) + " \u00B7 " + plural(cod.ordersWithCod(), "order")));
            rows.append(detailRow("Cash on delivery received " + when(b),
                    inr(cod.receivedThatDay()) + " \u00B7 " + plural(cod.receivedCount(), "order")));
            sb.append(detailTable(rows.toString()));
        }

        Dues dues = b.dues();
        sb.append(subHeading("Still to collect, across all dates (as of this morning)"));
        if (dues == null) {
            return sb.append(unavailable()).toString();
        }
        StringBuilder rows = new StringBuilder();
        rows.append(detailRow("Customers still owe on active orders", inr(dues.customersOwe())));
        rows.append(detailRow("Courier owes us (delivered, not paid out yet)", inr(dues.courierOwes())));
        if (dues.pendingClaims() > 0 || dues.claimsAmount().signum() > 0) {
            rows.append(detailRow("Lost-parcel claims to settle (" + dues.pendingClaims() + ")", inr(dues.claimsAmount())));
        }
        rows.append(detailRowStrong("Total to collect", inr(dues.total())));
        sb.append(detailTable(rows.toString()));
        if (dues.overSlaOrders() > 0) {
            sb.append(noteBad(inr(dues.overSlaAmount()) + " from the courier (" + plural(dues.overSlaOrders(), "order")
                    + ") is older than " + dues.slaDays() + " days and worth chasing."));
        }
        return sb.toString();
    }

    // --- Top salespeople -------------------------------------------------------------------------

    private String topSection(MorningBrief b) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading("Top 3 salespeople of the day", "By value of orders placed " + when(b) + "."));
        List<PersonRow> top = b.topSalespeople();
        if (top == null) {
            return sb.append(unavailable()).toString();
        }
        if (top.isEmpty()) {
            return sb.append(note("No salesperson placed an order " + when(b) + ".")).toString();
        }
        StringBuilder rows = new StringBuilder();
        rows.append(headRow(new String[]{"", "Salesperson", "Orders", "Sales"}, new boolean[]{false, false, true, true}));
        for (PersonRow p : top) {
            String name = esc(p.name());
            if (p.bonus() != null && p.bonus().signum() > 0) {
                name += "<br><span style=\"font-size:11px;font-weight:bold;color:" + GOOD + ";\">Earned "
                        + esc(inr(p.bonus())) + " bonus</span>";
            }
            rows.append(row(new String[]{medal(p.rank()), name, String.valueOf(p.orders()), esc(inr(p.sales()))},
                    new boolean[]{false, false, true, true}, new boolean[]{false, true, false, true}, false));
        }
        sb.append(grid(rows.toString()));
        return sb.toString();
    }

    private static String medal(int rank) {
        String color = MEDALS[Math.min(Math.max(rank, 1), 3) - 1];
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td width=\"24\" height=\"24\" align=\"center\" style=\"width:24px;height:24px;border-radius:12px;"
                + "background-color:" + color + ";color:#ffffff;font-size:12px;font-weight:bold;\">" + rank
                + "</td></tr></table>";
    }

    // --- Incentive -------------------------------------------------------------------------------

    private String incentiveSection(MorningBrief b) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading("Daily sales incentive", "Bonus a salesperson earns for reaching a sales slab in one day."));
        Incentive inc = b.incentive();
        if (inc == null) {
            return sb.append(unavailable()).toString();
        }
        if (!inc.active()) {
            return sb.append(callout("#f4f6f4", MUTED, "No daily incentive program was active "
                    + (b.yesterday() ? "yesterday" : "on " + SHORT_DAY.format(b.day())) + ". "
                    + "You can set slabs up under Settings \u2192 Incentives.")).toString();
        }

        sb.append(note("In force: " + slabText(inc.slabs()) + "."));
        int earned = inc.earners().size();
        if (earned == 0) {
            String nobody = "Nobody reached the first slab (" + inr(inc.slabs().get(0).minSales()) + ") " + when(b) + ".";
            if (b.topSalespeople() != null && !b.topSalespeople().isEmpty()) {
                PersonRow best = b.topSalespeople().get(0);
                nobody += " Closest: " + best.name() + " with " + inr(best.sales()) + ".";
            }
            sb.append(callout("#fff8e6", "#7a5b00", nobody));
        } else {
            sb.append(callout("#e8f5e9", "#1b5e20", "<strong>" + earned + " of " + inc.salespeople()
                    + " salespeople</strong> earned a bonus " + esc(when(b)) + " \u00B7 <strong>"
                    + esc(inr(inc.totalBonus())) + "</strong> in total", true));
            StringBuilder rows = new StringBuilder();
            rows.append(headRow(new String[]{"Salesperson", "Sales", "Slab", "Earned"},
                    new boolean[]{false, true, true, true}));
            for (Earner e : inc.earners()) {
                rows.append(row(new String[]{esc(e.name()), esc(inr(e.sales())),
                                e.slab() == null ? "\u2014" : esc(inr(e.slab()) + "+"), esc(inr(e.bonus()))},
                        new boolean[]{false, true, true, true}, new boolean[]{true, false, false, true}, false));
            }
            sb.append(grid(rows.toString()));
        }
        if (inc.monthBonus().signum() > 0) {
            sb.append(note(MONTH.format(b.day()) + " so far: " + inr(inc.monthBonus()) + " in bonuses across "
                    + plural(inc.monthBonusDays(), "bonus day") + "."));
        }
        return sb.toString();
    }

    private static String slabText(List<Slab> slabs) {
        StringBuilder sb = new StringBuilder();
        for (Slab s : slabs) {
            if (sb.length() > 0) {
                sb.append(" \u00B7 ");
            }
            sb.append(inr(s.minSales())).append("+ earns ").append(inr(s.bonus()));
        }
        return sb.toString();
    }

    // --- Highlights ------------------------------------------------------------------------------

    private String highlightsSection(MorningBrief b) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading("More from " + (b.yesterday() ? "yesterday" : SHORT_DAY.format(b.day())), null));
        Highlights h = b.highlights();
        if (h == null) {
            return sb.append(unavailable()).toString();
        }
        StringBuilder rows = new StringBuilder();
        long customers = h.newCustomers() + h.returningCustomers();
        rows.append(detailRow("Customers who ordered", customers == 0 ? "None"
                : customers + " \u00B7 " + h.newCustomers() + " new, " + h.returningCustomers() + " returning"));
        rows.append(detailRow("Delivered " + when(b), h.deliveredOrders() == 0 ? "None"
                : plural(h.deliveredOrders(), "order") + " \u00B7 " + inr(h.deliveredValue())));
        rows.append(detailRowTone("Failed or returned " + when(b), h.failedOrders() == 0 ? "None"
                : plural(h.failedOrders(), "order") + " \u00B7 " + inr(h.failedValue()), h.failedOrders() > 0));
        rows.append(detailRowTone("Rejected or cancelled (placed " + when(b) + ")", h.lostOrders() == 0 ? "None"
                : plural(h.lostOrders(), "order") + " \u00B7 " + inr(h.lostValue()), h.lostOrders() > 0));
        sb.append(detailTable(rows.toString()));

        if (!h.bestSellers().isEmpty()) {
            sb.append(subHeading("Best sellers"));
            StringBuilder products = new StringBuilder();
            products.append(headRow(new String[]{"Product", "Units", "Sales"}, new boolean[]{false, true, true}));
            for (ProductRow p : h.bestSellers()) {
                products.append(row(new String[]{esc(p.name()), String.valueOf(p.units()), esc(inr(p.sales()))},
                        new boolean[]{false, true, true}, new boolean[]{true, false, true}, false));
            }
            sb.append(grid(products.toString()));
        }
        return sb.toString();
    }

    // --- Needs attention -------------------------------------------------------------------------

    private String attentionSection(MorningBrief b) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading("Waiting for you this morning", null));
        Attention a = b.attention();
        if (a == null) {
            return sb.append(unavailable()).toString();
        }
        if (a.isEmpty()) {
            return sb.append(callout("#e8f5e9", "#1b5e20", "All clear \u2014 nothing is waiting.")).toString();
        }
        StringBuilder rows = new StringBuilder();
        addAttention(rows, "Orders waiting for approval", a.approvalsWaiting());
        addAttention(rows, "Payments waiting for verification", a.paymentsToVerify());
        addAttention(rows, "Failed deliveries to retry or return", a.failedDeliveries());
        addAttention(rows, "Shipments stuck with the courier booking", a.stuckShipments());
        sb.append(detailTable(rows.toString()));
        return sb.toString();
    }

    private void addAttention(StringBuilder rows, String label, long count) {
        if (count > 0) {
            rows.append(detailRowTone(label, String.valueOf(count), true));
        }
    }

    private String footnote() {
        return "<p style=\"margin:22px 0 0 0;font-size:11px;line-height:1.6;color:" + MUTED + ";\">"
                + "Sales leave out rejected, payment-rejected and cancelled orders. Money in banks is counted when "
                + "the payment was recorded. Cash that couriers pay out is not tied to a bank account in Shifa, so "
                + "it is shown as a total. All times are India Standard Time.</p>";
    }

    // ---------------------------------------------------------------------------------------------
    // Plain text
    // ---------------------------------------------------------------------------------------------

    String text(MorningBrief b) {
        MorningBrief.Headline h = b.headline();
        StringBuilder t = new StringBuilder();
        t.append("Shifa Herbal Remedies - morning brief\n");
        t.append(b.yesterday() ? "Yesterday" : "Report for").append(": ").append(LONG_DAY.format(b.day()))
                .append("\n\n");
        t.append("SALES: ").append(inr(h.sales())).append(" from ").append(plural(h.orders(), "order"));
        if (h.orders() > 0) {
            t.append(" (average ").append(inr(h.averageOrderValue())).append(")");
        }
        t.append("\n");
        t.append("  ").append(textDelta("the day before", h.sales(), h.previousDay())).append("\n");
        t.append("  ").append(textDelta("the same day last week", h.sales(), h.sameWeekdayLastWeek())).append("\n");
        t.append("  ").append(MONTH.format(b.day())).append(" so far: ").append(inr(h.monthSales())).append(" from ")
                .append(plural(h.monthOrders(), "order")).append("\n\n");

        t.append("SALES BY SOURCE\n");
        for (ChannelRow c : b.channels()) {
            t.append("  ").append(c.label()).append(": ").append(plural(c.orders(), "order")).append(", ")
                    .append(inr(c.sales())).append(", to collect on delivery ").append(inr(c.codToCollect())).append("\n");
        }
        t.append("  Total: ").append(plural(b.total().orders(), "order")).append(", ").append(inr(b.total().sales()))
                .append(", to collect on delivery ").append(inr(b.total().codToCollect())).append("\n\n");

        t.append("COLLECTED ").append(when(b).toUpperCase(Locale.ENGLISH)).append(", BY BANK / QR ACCOUNT\n");
        if (b.banks() == null) {
            t.append("  (not available)\n");
        } else if (b.banks().rows().isEmpty()) {
            t.append("  No payments were recorded ").append(when(b)).append(".\n");
        } else {
            for (BankRow r : b.banks().rows()) {
                t.append("  ").append(r.account()).append(": ").append(inr(r.amount())).append(" (")
                        .append(plural(r.payments(), "payment")).append(")\n");
            }
            t.append("  Total: ").append(inr(b.banks().total())).append("\n");
            if (b.banks().awaitingVerification().signum() > 0) {
                t.append("  Waiting for the payment verifier: ").append(inr(b.banks().awaitingVerification())).append("\n");
            }
            if (b.banks().rejected().signum() > 0) {
                t.append("  Rejected by the payment verifier: ").append(inr(b.banks().rejected())).append("\n");
            }
        }
        t.append("\n");

        t.append("CASH ON DELIVERY\n");
        if (b.cod() == null) {
            t.append("  (not available)\n");
        } else {
            t.append("  To collect on delivery (orders placed ").append(when(b)).append("): ")
                    .append(inr(b.cod().toCollect())).append(" on ")
                    .append(plural(b.cod().ordersWithCod(), "order")).append("\n");
            t.append("  Received ").append(when(b)).append(": ").append(inr(b.cod().receivedThatDay())).append(" on ")
                    .append(plural(b.cod().receivedCount(), "order")).append("\n");
        }
        if (b.dues() != null) {
            Dues d = b.dues();
            t.append("  Still to collect, all dates: ").append(inr(d.total())).append(" (customers ")
                    .append(inr(d.customersOwe())).append(", courier ").append(inr(d.courierOwes()))
                    .append(", claims ").append(inr(d.claimsAmount())).append(")\n");
            if (d.overSlaOrders() > 0) {
                t.append("  Older than ").append(d.slaDays()).append(" days from the courier: ")
                        .append(inr(d.overSlaAmount())).append(" (").append(plural(d.overSlaOrders(), "order"))
                        .append(")\n");
            }
        }
        t.append("\n");

        t.append("TOP 3 SALESPEOPLE\n");
        if (b.topSalespeople() == null) {
            t.append("  (not available)\n");
        } else if (b.topSalespeople().isEmpty()) {
            t.append("  No salesperson placed an order ").append(when(b)).append(".\n");
        } else {
            for (PersonRow p : b.topSalespeople()) {
                t.append("  ").append(p.rank()).append(". ").append(p.name()).append(" - ")
                        .append(inr(p.sales())).append(" (").append(plural(p.orders(), "order")).append(")");
                if (p.bonus() != null && p.bonus().signum() > 0) {
                    t.append(" - earned ").append(inr(p.bonus())).append(" bonus");
                }
                t.append("\n");
            }
        }
        t.append("\n");

        t.append("DAILY SALES INCENTIVE\n");
        Incentive inc = b.incentive();
        if (inc == null) {
            t.append("  (not available)\n");
        } else if (!inc.active()) {
            t.append("  No daily incentive program was active ").append(when(b)).append(".\n");
        } else {
            t.append("  In force: ").append(slabText(inc.slabs())).append("\n");
            t.append("  ").append(inc.earners().size()).append(" of ").append(inc.salespeople())
                    .append(" salespeople earned a bonus, ").append(inr(inc.totalBonus())).append(" in total\n");
            for (Earner e : inc.earners()) {
                t.append("  - ").append(e.name()).append(": ").append(inr(e.bonus())).append(" (sold ")
                        .append(inr(e.sales())).append(")\n");
            }
            if (inc.monthBonus().signum() > 0) {
                t.append("  ").append(MONTH.format(b.day())).append(" so far: ").append(inr(inc.monthBonus()))
                        .append(" across ").append(plural(inc.monthBonusDays(), "bonus day")).append("\n");
            }
        }
        t.append("\n");

        t.append("MORE FROM ").append((b.yesterday() ? "yesterday" : SHORT_DAY.format(b.day())).toUpperCase(Locale.ENGLISH))
                .append("\n");
        Highlights hl = b.highlights();
        if (hl == null) {
            t.append("  (not available)\n");
        } else {
            t.append("  Customers who ordered: ").append(hl.newCustomers() + hl.returningCustomers()).append(" (")
                    .append(hl.newCustomers()).append(" new, ").append(hl.returningCustomers()).append(" returning)\n");
            t.append("  Delivered: ").append(plural(hl.deliveredOrders(), "order")).append(", ")
                    .append(inr(hl.deliveredValue())).append("\n");
            t.append("  Failed or returned: ").append(plural(hl.failedOrders(), "order")).append(", ")
                    .append(inr(hl.failedValue())).append("\n");
            t.append("  Rejected or cancelled: ").append(plural(hl.lostOrders(), "order")).append(", ")
                    .append(inr(hl.lostValue())).append("\n");
            for (ProductRow p : hl.bestSellers()) {
                t.append("  Best seller: ").append(p.name()).append(" - ").append(p.units()).append(" units, ")
                        .append(inr(p.sales())).append("\n");
            }
        }
        t.append("\n");

        t.append("WAITING FOR YOU\n");
        Attention a = b.attention();
        if (a == null) {
            t.append("  (not available)\n");
        } else if (a.isEmpty()) {
            t.append("  All clear.\n");
        } else {
            textAttention(t, "Orders waiting for approval", a.approvalsWaiting());
            textAttention(t, "Payments waiting for verification", a.paymentsToVerify());
            textAttention(t, "Failed deliveries to retry or return", a.failedDeliveries());
            textAttention(t, "Shipments stuck with the courier booking", a.stuckShipments());
        }
        t.append("\n--\n").append(brand.brandName()).append("\n")
                .append(brand.footerCredit()).append(" (").append(brand.footerCreditUrl()).append(")\n");
        return t.toString();
    }

    private static void textAttention(StringBuilder t, String label, long count) {
        if (count > 0) {
            t.append("  ").append(label).append(": ").append(count).append("\n");
        }
    }

    private static String textDelta(String what, BigDecimal now, Comparison c) {
        String when = SHORT_DAY.format(c.day());
        BigDecimal pct = MorningBrief.changePercent(now, c.sales());
        if (pct == null) {
            return "No sales on " + when + " (" + what + ") to compare with.";
        }
        return percent(pct.abs()) + (pct.signum() >= 0 ? " above " : " below ") + what + " (" + when + ": "
                + inr(c.sales()) + ")";
    }

    // ---------------------------------------------------------------------------------------------
    // HTML building blocks
    // ---------------------------------------------------------------------------------------------

    private String heading(String title, String subtitle) {
        String h = "<div style=\"margin:26px 0 " + (subtitle == null ? "10px" : "2px") + " 0;padding-left:10px;"
                + "border-left:4px solid " + brand.accentColor() + ";font-size:15px;line-height:1.3;font-weight:bold;"
                + "color:" + brand.primaryColor() + ";\">" + esc(title) + "</div>";
        if (subtitle != null) {
            h += "<div style=\"margin:0 0 10px 14px;font-size:12px;line-height:1.4;color:" + MUTED + ";\">"
                    + esc(subtitle) + "</div>";
        }
        return h;
    }

    private String subHeading(String title) {
        return "<div style=\"margin:14px 0 6px 0;font-size:12px;font-weight:bold;letter-spacing:0.3px;color:"
                + INK + ";\">" + esc(title) + "</div>";
    }

    private String note(String text) {
        return "<p style=\"margin:0 0 8px 0;font-size:12px;line-height:1.5;color:" + MUTED + ";\">" + esc(text) + "</p>";
    }

    private String noteBad(String text) {
        return "<p style=\"margin:0 0 8px 0;font-size:12px;line-height:1.5;font-weight:bold;color:" + BAD + ";\">"
                + esc(text) + "</p>";
    }

    private String unavailable() {
        return note("This section could not be loaded today.");
    }

    private String callout(String background, String color, String text) {
        return callout(background, color, esc(text), true);
    }

    /** A shaded box; {@code html} is already escaped (the flag only distinguishes it from the plain overload). */
    private String callout(String background, String color, String html, boolean alreadyHtml) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:0 0 10px 0;background-color:" + background + ";border-radius:8px;\"><tr>"
                + "<td style=\"padding:12px 14px;font-size:13px;line-height:1.5;color:" + color + ";\">" + html
                + "</td></tr></table>";
    }

    private String grid(String rowsHtml) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:0 0 10px 0;border:1px solid #e2e6e1;border-radius:8px;border-collapse:separate;"
                + "overflow:hidden;\">" + rowsHtml + "</table>";
    }

    private String headRow(String[] heads, boolean[] right) {
        StringBuilder sb = new StringBuilder("<tr>");
        for (int i = 0; i < heads.length; i++) {
            sb.append("<th align=\"").append(right[i] ? "right" : "left").append("\" style=\"padding:8px 8px;"
                    + "font-size:10px;text-transform:uppercase;letter-spacing:0.5px;color:").append(MUTED)
                    .append(";background-color:#f0f2ef;border-bottom:1px solid #e2e6e1;\">")
                    .append(esc(heads[i])).append("</th>");
        }
        return sb.append("</tr>").toString();
    }

    /** A data row. Cells are already-safe HTML; plain numbers pass through unchanged. */
    private String row(String[] cells, boolean[] right, boolean[] bold, boolean total) {
        StringBuilder sb = new StringBuilder("<tr>");
        for (int i = 0; i < cells.length; i++) {
            sb.append("<td align=\"").append(right[i] ? "right" : "left").append("\" valign=\"middle\" "
                    + "style=\"padding:9px 8px;font-size:13px;color:").append(INK).append(";")
                    .append(bold[i] ? "font-weight:bold;" : "")
                    .append(total ? "background-color:" + SOFT + ";border-top:2px solid #e2e6e1;"
                            : "border-bottom:1px solid " + LINE + ";")
                    .append("\">").append(cells[i]).append("</td>");
        }
        return sb.append("</tr>").toString();
    }

    private String detailTable(String rowsHtml) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:0 0 10px 0;border:1px solid #e2e6e1;border-radius:8px;border-collapse:separate;"
                + "overflow:hidden;\">" + rowsHtml + "</table>";
    }

    private String detailRow(String label, String value) {
        return detailRowTone(label, value, false);
    }

    private String detailRowTone(String label, String value, boolean bad) {
        return "<tr><td style=\"padding:9px 12px;font-size:12px;color:" + MUTED + ";border-bottom:1px solid " + LINE
                + ";width:55%;\">" + esc(label) + "</td>"
                + "<td align=\"right\" style=\"padding:9px 12px;font-size:13px;font-weight:bold;color:"
                + (bad ? BAD : INK) + ";border-bottom:1px solid " + LINE + ";\">" + esc(value) + "</td></tr>";
    }

    private String detailRowStrong(String label, String value) {
        return "<tr><td style=\"padding:10px 12px;font-size:13px;font-weight:bold;color:" + INK
                + ";background-color:" + SOFT + ";\">" + esc(label) + "</td>"
                + "<td align=\"right\" style=\"padding:10px 12px;font-size:14px;font-weight:bold;color:"
                + brand.primaryColor() + ";background-color:" + SOFT + ";\">" + esc(value) + "</td></tr>";
    }

    // ---------------------------------------------------------------------------------------------
    // Formatting
    // ---------------------------------------------------------------------------------------------

    private static String esc(String value) {
        return EmailRenderer.esc(value);
    }

    private static String inr(BigDecimal amount) {
        return IncentiveFormat.inr(amount);
    }

    private static String plural(long n, String singular) {
        return n + " " + singular + (n == 1 ? "" : "s");
    }

    private static String percent(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** Whole-number share of {@code part} in {@code whole}, never more than 100. */
    private static int shareOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() <= 0 || part == null || part.signum() <= 0) {
            return 0;
        }
        int pct = part.multiply(BigDecimal.valueOf(100)).divide(whole, 0, RoundingMode.HALF_UP).intValue();
        if (pct == 0) {
            return 1;
        }
        return Math.min(pct, 100);
    }
}
