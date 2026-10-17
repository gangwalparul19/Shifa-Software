package com.shifa.oms.reporting;

import com.shifa.oms.reporting.domain.TabularData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link TabularData} into a PNG bar chart (Java2D, no external
 * charting dependency) so the PDF export can show a professional bar graph above
 * the detailed table. The chart is purely a visual summary <em>derived from</em>
 * the same report table — it never changes the table's rows/columns, so report
 * fidelity (Property 24) is unaffected.
 *
 * <p>Two layouts are produced depending on the report shape:
 * <ul>
 *   <li><b>Vertical bars</b> for time-series reports (a date/month/week label
 *       column + a sales column): one bar per period, left-to-right.</li>
 *   <li><b>Horizontal bars</b> for category reports (product / state / customer /
 *       status / lead-source / salesperson): the top-N categories by value,
 *       which keeps long category labels readable.</li>
 * </ul>
 *
 * <p>The renderer chooses the "value" column by finding the first header
 * matching sales/revenue/amount/total, falling back to the first "orders"/count
 * column, falling back to the last column — mirroring the frontend trend-chart
 * heuristic so the chart reflects the report's headline metric.
 */
@Component
public class ReportChartRenderer {

    private static final Logger log = LoggerFactory.getLogger(ReportChartRenderer.class);

    // Brand palette (mirrors PdfReportExporter).
    private static final Color BRAND_GREEN = new Color(0x1F, 0x7A, 0x4D);
    private static final Color BRAND_GREEN_LIGHT = new Color(0x3F, 0x9A, 0x6D);
    private static final Color GOLD = new Color(0xC8, 0xA2, 0x4A);
    private static final Color AXIS = new Color(0x9A, 0xA8, 0xA0);
    private static final Color GRID = new Color(0xE6, 0xEC, 0xE8);
    private static final Color LABEL = new Color(0x24, 0x3B, 0x30);
    private static final Color MUTED = new Color(0x6B, 0x7B, 0x72);
    private static final Color WHITE = Color.WHITE;

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 16);
    private static final Font AXIS_FONT = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font VALUE_FONT = new Font("SansSerif", Font.BOLD, 11);

    /** Chart canvas size (px). Landscape A4 at ~96dpi is ~1050px wide usable. */
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 380;
    private static final int MAX_CATEGORY_BARS = 10;

    /**
     * Renders a bar-chart PNG for the report table, or {@code null} when no
     * numeric series can be derived (so the caller simply omits the chart). The
     * {@code valueLabel} (e.g. "Total Sales") is drawn as the chart subtitle.
     */
    public byte[] renderBarChart(String title, TabularData table) {
        try {
            Series series = extractSeries(table);
            if (series == null || series.points.isEmpty()) {
                return null;
            }
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(WHITE);
                g.fillRect(0, 0, WIDTH, HEIGHT);

                drawTitle(g, title, series.valueLabel);

                if (series.horizontal) {
                    drawHorizontalBars(g, series);
                } else {
                    drawVerticalBars(g, series);
                }
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            // Charts are decorative — never fail the export because the chart failed.
            log.warn("Failed to render report bar chart; exporting PDF without a chart.", e);
            return null;
        }
    }

    // --- Drawing ------------------------------------------------------------

    private void drawTitle(Graphics2D g, String title, String valueLabel) {
        g.setFont(TITLE_FONT);
        g.setColor(BRAND_GREEN);
        g.drawString(title == null ? "Report" : title, 24, 28);
        g.setFont(AXIS_FONT);
        g.setColor(MUTED);
        g.drawString(valueLabel, 24, 46);
        // Gold accent underline.
        g.setColor(GOLD);
        g.fillRect(24, 52, 70, 3);
    }

    private void drawVerticalBars(Graphics2D g, Series series) {
        int left = 70;
        int right = WIDTH - 30;
        int top = 70;
        int bottom = HEIGHT - 60;
        int plotWidth = right - left;
        int plotHeight = bottom - top;

        double max = series.maxValue();
        drawYAxisAndGrid(g, left, right, top, bottom, max);

        int n = series.points.size();
        double slot = (double) plotWidth / n;
        int barWidth = (int) Math.max(6, Math.min(48, slot * 0.6));
        g.setFont(AXIS_FONT);

        for (int i = 0; i < n; i++) {
            Point p = series.points.get(i);
            double frac = max <= 0 ? 0 : p.value / max;
            int barHeight = (int) Math.round(frac * plotHeight);
            int cx = (int) (left + slot * i + slot / 2);
            int x = cx - barWidth / 2;
            int y = bottom - barHeight;

            g.setColor(i % 2 == 0 ? BRAND_GREEN : BRAND_GREEN_LIGHT);
            g.fillRoundRect(x, y, barWidth, Math.max(1, barHeight), 6, 6);

            // Value label above the bar (compact).
            if (barHeight > 0) {
                g.setColor(LABEL);
                g.setFont(VALUE_FONT);
                String vlabel = compact(p.value);
                int vw = g.getFontMetrics().stringWidth(vlabel);
                g.drawString(vlabel, cx - vw / 2, y - 4);
            }

            // X label under the axis (trim to fit the slot).
            g.setColor(MUTED);
            g.setFont(AXIS_FONT);
            String xlabel = trimToWidth(g, p.label, (int) slot - 2);
            int lw = g.getFontMetrics().stringWidth(xlabel);
            g.drawString(xlabel, cx - lw / 2, bottom + 16);
        }
    }

    private void drawHorizontalBars(Graphics2D g, Series series) {
        int left = 180;
        int right = WIDTH - 90;
        int top = 70;
        int bottom = HEIGHT - 30;
        int plotWidth = right - left;
        int plotHeight = bottom - top;

        double max = series.maxValue();
        int n = series.points.size();
        double slot = (double) plotHeight / n;
        int barHeight = (int) Math.max(8, Math.min(32, slot * 0.6));
        g.setFont(AXIS_FONT);

        // Vertical baseline.
        g.setColor(AXIS);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(left, top, left, bottom);

        for (int i = 0; i < n; i++) {
            Point p = series.points.get(i);
            double frac = max <= 0 ? 0 : p.value / max;
            int barLen = (int) Math.round(frac * plotWidth);
            int cy = (int) (top + slot * i + slot / 2);
            int y = cy - barHeight / 2;

            g.setColor(i % 2 == 0 ? BRAND_GREEN : BRAND_GREEN_LIGHT);
            g.fillRoundRect(left, y, Math.max(1, barLen), barHeight, 6, 6);

            // Category label to the left of the baseline.
            g.setColor(LABEL);
            g.setFont(AXIS_FONT);
            String cat = trimToWidth(g, p.label, left - 30);
            int lw = g.getFontMetrics().stringWidth(cat);
            g.drawString(cat, left - 8 - lw, cy + 4);

            // Value label at the end of the bar.
            g.setColor(MUTED);
            g.setFont(VALUE_FONT);
            g.drawString(compact(p.value), left + barLen + 6, cy + 4);
        }
    }

    private void drawYAxisAndGrid(Graphics2D g, int left, int right, int top, int bottom, double max) {
        g.setStroke(new BasicStroke(1f));
        g.setFont(AXIS_FONT);
        int ticks = 4;
        for (int t = 0; t <= ticks; t++) {
            double frac = (double) t / ticks;
            int y = (int) Math.round(bottom - frac * (bottom - top));
            g.setColor(GRID);
            g.drawLine(left, y, right, y);
            g.setColor(MUTED);
            String lbl = compact(max * frac);
            int lw = g.getFontMetrics().stringWidth(lbl);
            g.drawString(lbl, left - 6 - lw, y + 4);
        }
        // Axis baseline.
        g.setColor(AXIS);
        g.drawLine(left, bottom, right, bottom);
    }

    // --- Series extraction --------------------------------------------------

    /** A single (label, value) chart point. */
    private record Point(String label, double value) {
    }

    /** A derived chart series plus its orientation and value-axis label. */
    private static final class Series {
        final List<Point> points;
        final String valueLabel;
        final boolean horizontal;

        Series(List<Point> points, String valueLabel, boolean horizontal) {
            this.points = points;
            this.valueLabel = valueLabel;
            this.horizontal = horizontal;
        }

        double maxValue() {
            double max = 0;
            for (Point p : points) {
                max = Math.max(max, p.value);
            }
            return max;
        }
    }

    /**
     * Derives the chartable series from the report table: picks the value column
     * (sales/amount, else orders/count, else last), the label column (first), and
     * the orientation (time-series headers → vertical; everything else →
     * horizontal top-N). Returns {@code null} when nothing numeric is chartable.
     */
    private Series extractSeries(TabularData table) {
        List<String> headers = table.headers();
        if (headers.isEmpty() || table.rows().isEmpty()) {
            return null;
        }
        int valueIdx = pickValueColumn(headers);
        if (valueIdx < 0) {
            return null;
        }
        String valueLabel = headers.get(valueIdx);
        boolean timeSeries = isTimeSeries(headers.get(0));

        List<Point> points = new ArrayList<>();
        for (List<String> row : table.rows()) {
            if (valueIdx >= row.size()) {
                continue;
            }
            Double v = parseNumber(row.get(valueIdx));
            if (v == null) {
                continue;
            }
            String label = row.isEmpty() ? "" : nz(row.get(0));
            points.add(new Point(label, v));
        }
        if (points.isEmpty()) {
            return null;
        }

        if (timeSeries) {
            // Keep chronological order (the aggregator already sorts ascending).
            return new Series(points, "By " + headers.get(0) + " \u2014 " + valueLabel, false);
        }
        // Category report: sort by value desc and keep the top N for readability.
        points.sort((a, b) -> Double.compare(b.value, a.value));
        List<Point> top = points.size() > MAX_CATEGORY_BARS
                ? new ArrayList<>(points.subList(0, MAX_CATEGORY_BARS))
                : points;
        String scope = points.size() > MAX_CATEGORY_BARS
                ? "Top " + MAX_CATEGORY_BARS + " by " + valueLabel
                : "By " + valueLabel;
        return new Series(top, scope, true);
    }

    /** First header matching sales/revenue/amount/total, else an orders/count column, else last. */
    private static int pickValueColumn(List<String> headers) {
        int salesIdx = -1;
        int ordersIdx = -1;
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i) == null ? "" : headers.get(i).toLowerCase();
            if (salesIdx < 0 && (h.contains("sales") || h.contains("revenue")
                    || h.contains("amount") || h.contains("total"))) {
                // Skip a pure "On-Delivery Amount"-style column only if a better one exists later;
                // first match wins, which for our reports is the sales/total column.
                salesIdx = i;
            }
            if (ordersIdx < 0 && (h.equals("orders") || h.contains("order count") || h.equals("count"))) {
                ordersIdx = i;
            }
        }
        if (salesIdx >= 0) {
            return salesIdx;
        }
        if (ordersIdx >= 0) {
            return ordersIdx;
        }
        // Fall back to the last column only if it parses as a number for some row.
        return headers.size() - 1;
    }

    /** Whether the first column is a time bucket (date / month / week). */
    private static boolean isTimeSeries(String firstHeader) {
        if (firstHeader == null) {
            return false;
        }
        String h = firstHeader.toLowerCase();
        return h.equals("date") || h.equals("month") || h.equals("week")
                || h.contains("week") || h.contains("month") || h.contains("date");
    }

    /** Parses a plain/formatted number ("1234.50", "Rs. 1,200", "+5%"), or null. */
    private static Double parseNumber(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Compact Indian-style magnitude label (1.2Cr / 3.4L / 5.6k / 123). */
    private static String compact(double value) {
        double v = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        if (v >= 1_00_00_000d) {
            return sign + trim(v / 1_00_00_000d) + "Cr";
        }
        if (v >= 1_00_000d) {
            return sign + trim(v / 1_00_000d) + "L";
        }
        if (v >= 1_000d) {
            return sign + trim(v / 1_000d) + "k";
        }
        return sign + Long.toString(Math.round(v));
    }

    private static String trim(double v) {
        String s = String.format("%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** Trims a label to fit the given pixel width, adding an ellipsis when cut. */
    private static String trimToWidth(Graphics2D g, String label, int maxWidth) {
        String s = nz(label);
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxWidth || maxWidth <= 0) {
            return s;
        }
        String ell = "\u2026";
        int ellW = fm.stringWidth(ell);
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            int cw = fm.charWidth(s.charAt(i));
            if (w + cw + ellW > maxWidth) {
                break;
            }
            sb.append(s.charAt(i));
            w += cw;
        }
        return sb.length() == 0 ? ell : sb.append(ell).toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
