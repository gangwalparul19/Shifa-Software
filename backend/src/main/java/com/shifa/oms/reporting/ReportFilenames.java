package com.shifa.oms.reporting;

import org.springframework.http.ContentDisposition;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;

/**
 * The single naming convention for every downloaded report, so a file is
 * recognisable the moment it lands in the Downloads folder and nobody has to
 * rename it afterwards:
 *
 * <pre>shifa-&lt;report-name&gt;_&lt;date range&gt;.&lt;ext&gt;</pre>
 *
 * for example {@code shifa-daily-sales-report_2026-10-02_to_2026-10-31.pdf} or
 * {@code shifa-customer-wise-report_all-time.xlsx}. The date range uses ISO
 * dates, so files for the same report sort chronologically.
 *
 * <p>Names are built only from {@code [a-z0-9._-]}, whatever text goes in (a
 * report title, a salesperson's name): that keeps them valid on every operating
 * system and safe to place in a {@code Content-Disposition} header.
 *
 * <p>The Angular admin app has a matching helper
 * ({@code shared/report-filename.util.ts}) used when it has to name a file
 * itself; both are tested against the same examples.
 */
public final class ReportFilenames {

    /** Every report file starts with this, so they are easy to spot and search. */
    public static final String PREFIX = "shifa";

    private static final int MAX_SLUG_LENGTH = 80;
    private static final String DEFAULT_SLUG = "report";
    private static final String DEFAULT_EXTENSION = "bin";

    private ReportFilenames() {
    }

    /**
     * {@code shifa-<slug of title>_<range>.<extension>}.
     *
     * @param title     the human report name, e.g. "Daily Sales Report"
     * @param from      first day covered, or {@code null} for no lower bound
     * @param to        last day covered (inclusive), or {@code null} for no upper bound
     * @param extension file extension without the dot, e.g. "pdf"
     */
    public static String of(String title, LocalDate from, LocalDate to, String extension) {
        return PREFIX + "-" + slug(title) + "_" + rangePart(from, to) + "." + extension(extension);
    }

    /** A lowercase, dash-separated ASCII form of {@code text}; "report" when nothing usable is left. */
    public static String slug(String text) {
        return slug(text, DEFAULT_SLUG);
    }

    /** As {@link #slug(String)} but with a caller-supplied fallback for text that has no usable characters. */
    public static String slug(String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? fallback : slug;
    }

    /**
     * How the covered dates read in a filename: {@code 2026-10-02_to_2026-10-31},
     * a single {@code 2026-10-02}, {@code from-2026-10-02}, {@code up-to-2026-10-31},
     * or {@code all-time} when neither bound is set.
     */
    public static String rangePart(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return "all-time";
        }
        if (from != null && to != null) {
            return from.equals(to) ? from.toString() : from + "_to_" + to;
        }
        return from != null ? "from-" + from : "up-to-" + to;
    }

    /** An {@code attachment} Content-Disposition value for an already-safe filename. */
    public static String contentDisposition(String filename) {
        return ContentDisposition.attachment().filename(filename).build().toString();
    }

    private static String extension(String extension) {
        if (extension == null) {
            return DEFAULT_EXTENSION;
        }
        String cleaned = extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return cleaned.isEmpty() ? DEFAULT_EXTENSION : cleaned;
    }
}
