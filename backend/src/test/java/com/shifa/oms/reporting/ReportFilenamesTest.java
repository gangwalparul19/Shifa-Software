package com.shifa.oms.reporting;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one naming convention for every downloaded report:
 * {@code shifa-<report-name>_<date range>.<ext>}, e.g.
 * {@code shifa-daily-sales-report_2026-10-02_to_2026-10-31.pdf}. Names must be
 * self-describing (so nobody renames a file after downloading it), sort by
 * date, and be safe in a {@code Content-Disposition} header whatever text goes
 * into them.
 */
class ReportFilenamesTest {

    private static final LocalDate OCT_2 = LocalDate.of(2026, 10, 2);
    private static final LocalDate OCT_31 = LocalDate.of(2026, 10, 31);

    @Test
    void namesAReportAfterItsTitleAndDateRange() {
        assertThat(ReportFilenames.of("Daily Sales Report", OCT_2, OCT_31, "pdf"))
                .isEqualTo("shifa-daily-sales-report_2026-10-02_to_2026-10-31.pdf");
    }

    @Test
    void describesTheRangeWhenItIsOpenEndedOrASingleDay() {
        assertThat(ReportFilenames.rangePart(null, null)).isEqualTo("all-time");
        assertThat(ReportFilenames.rangePart(OCT_2, null)).isEqualTo("from-2026-10-02");
        assertThat(ReportFilenames.rangePart(null, OCT_31)).isEqualTo("up-to-2026-10-31");
        assertThat(ReportFilenames.rangePart(OCT_2, OCT_2)).isEqualTo("2026-10-02");
        assertThat(ReportFilenames.of("Customer-wise Report", null, null, "xlsx"))
                .isEqualTo("shifa-customer-wise-report_all-time.xlsx");
    }

    @Test
    void differentReportsAndRangesNeverShareAName() {
        String daily = ReportFilenames.of("Daily Sales Report", OCT_2, OCT_31, "pdf");
        String weekly = ReportFilenames.of("Weekly Sales Report", OCT_2, OCT_31, "pdf");
        String otherRange = ReportFilenames.of("Daily Sales Report", OCT_2, OCT_2, "pdf");
        String excel = ReportFilenames.of("Daily Sales Report", OCT_2, OCT_31, "xlsx");

        assertThat(java.util.List.of(daily, weekly, otherRange, excel)).doesNotHaveDuplicates();
    }

    @Test
    void turnsSymbolsAndAccentsIntoPlainWords() {
        assertThat(ReportFilenames.slug("Salesperson \u00d7 Product Report")).isEqualTo("salesperson-product-report");
        assertThat(ReportFilenames.slug("Returns & Refunds Report")).isEqualTo("returns-refunds-report");
        assertThat(ReportFilenames.slug("Pending from Courier")).isEqualTo("pending-from-courier");
        assertThat(ReportFilenames.slug("Caf\u00e9 \u00d1and\u00fa")).isEqualTo("cafe-nandu");
    }

    @Test
    void fallsBackWhenThereIsNothingUsableInTheTitle() {
        assertThat(ReportFilenames.slug(null)).isEqualTo("report");
        assertThat(ReportFilenames.slug("   ")).isEqualTo("report");
        assertThat(ReportFilenames.slug("\u0906\u0936\u093e")).isEqualTo("report"); // Devanagari only
        assertThat(ReportFilenames.slug("\u0906\u0936\u093e", "user-17")).isEqualTo("user-17");
        assertThat(ReportFilenames.of(null, OCT_2, OCT_31, "csv"))
                .isEqualTo("shifa-report_2026-10-02_to_2026-10-31.csv");
    }

    @Test
    void neverEmitsAnythingThatCouldBreakOrInjectIntoAHeader() {
        String hostile = "..\\..\\etc/passwd\"\r\nSet-Cookie: x=1; \u202e<script>";
        String name = ReportFilenames.of(hostile, OCT_2, OCT_31, "pdf");

        assertThat(name).matches("[a-z0-9._-]+");
        assertThat(name).doesNotContain("..").doesNotContain("/").doesNotContain("\\")
                .doesNotContain("\"").doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void capsVeryLongTitlesWithoutLeavingATrailingDash() {
        String longTitle = "word ".repeat(60);
        String slug = ReportFilenames.slug(longTitle);

        assertThat(slug.length()).isLessThanOrEqualTo(80);
        assertThat(slug).doesNotEndWith("-").doesNotStartWith("-");
    }

    @Test
    void sanitisesTheExtension() {
        assertThat(ReportFilenames.of("Orders", OCT_2, OCT_31, "XLSX")).endsWith(".xlsx");
        assertThat(ReportFilenames.of("Orders", OCT_2, OCT_31, "../x")).endsWith(".x");
        assertThat(ReportFilenames.of("Orders", OCT_2, OCT_31, null)).endsWith(".bin");
    }

    @Test
    void buildsAnAttachmentHeaderThatCarriesTheName() {
        assertThat(ReportFilenames.contentDisposition("shifa-orders_all-time.csv"))
                .isEqualTo("attachment; filename=\"shifa-orders_all-time.csv\"");
    }
}
