package com.shifa.oms.mail.template;

import com.shifa.oms.mail.DigestOrder;
import com.shifa.oms.mail.report.DailyReport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Small, immutable view-models fed to the {@link EmailRenderer}, one per email
 * type (Part 2). Deliberately plain data (no JPA entities) so rendering is a
 * pure function that can be unit-tested without a database, the scheduler, or
 * Mockito.
 */
public final class EmailModels {

    private EmailModels() {
    }

    /**
     * Order-confirmation email model (customer).
     *
     * @param customerName the customer's name for the greeting (may be blank)
     * @param orderCode    the human order code (e.g. {@code SHR-1024})
     * @param orderTotal   the order total; {@code null} hides the amount line
     */
    public record OrderConfirmation(String customerName, String orderCode, BigDecimal orderTotal) {
    }

    /**
     * Order-shipped / dispatched email model (customer).
     *
     * @param customerName      the customer's name for the greeting (may be blank)
     * @param orderCode         the order code
     * @param courierName       the courier company
     * @param awb               the AWB / tracking number
     * @param trackingUrl       the courier tracking link (absolute)
     * @param estimatedDelivery the estimated delivery date; {@code null} hides the line
     * @param codAmount         the COD amount to collect; {@code null}/zero hides the line
     */
    public record OrderShipped(
            String customerName,
            String orderCode,
            String courierName,
            String awb,
            String trackingUrl,
            LocalDate estimatedDelivery,
            BigDecimal codAmount) {
    }

    /**
     * Order-delivered email model (customer) — warm thank-you + review nudge.
     *
     * @param customerName the customer's name for the greeting (may be blank)
     * @param orderCode    the order code
     */
    public record OrderDelivered(String customerName, String orderCode) {
    }

    /**
     * Welcome email model (customer), sent on account registration.
     *
     * @param customerName the new customer's name (may be blank)
     */
    public record Welcome(String customerName) {
    }

    /**
     * Daily sales digest email model (admin/internal). A pure projection of a
     * day's orders into the metrics the digest reports.
     *
     * @param day          the day summarised
     * @param orderCount   qualifying orders (excludes rejected/cancelled)
     * @param totalSales   total sales value
     * @param prepaidCount number of prepaid orders
     * @param prepaidSales prepaid sales value
     * @param codCount     number of COD orders
     * @param codSales     COD sales value
     * @param excludedCount rejected/cancelled orders excluded from the totals
     */
    public record Digest(
            LocalDate day,
            int orderCount,
            BigDecimal totalSales,
            int prepaidCount,
            BigDecimal prepaidSales,
            int codCount,
            BigDecimal codSales,
            int excludedCount) {

        /**
         * Computes the digest metrics from a day's orders — a pure function
         * mirroring {@link com.shifa.oms.mail.DailyDigestJob#buildDigestBody}
         * (rejected/cancelled excluded from counts and totals).
         *
         * @param orders the orders created on {@code day} (any statuses)
         * @param day    the day being summarised
         * @return the computed metrics model
         */
        public static Digest from(List<DigestOrder> orders, LocalDate day) {
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal cod = BigDecimal.ZERO;
            BigDecimal prepaid = BigDecimal.ZERO;
            int codCount = 0;
            int prepaidCount = 0;
            int sales = 0;
            for (DigestOrder o : orders) {
                if (!o.countsAsSale()) {
                    continue;
                }
                sales++;
                BigDecimal amount = o.totalOrZero();
                total = total.add(amount);
                if (o.isCod()) {
                    cod = cod.add(amount);
                    codCount++;
                } else {
                    prepaid = prepaid.add(amount);
                    prepaidCount++;
                }
            }
            int excluded = orders.size() - sales;
            return new Digest(day, sales, total, prepaidCount, prepaid, codCount, cod, excluded);
        }
    }

    /**
     * Consolidated daily report email model (admin/internal). A thin wrapper over
     * the pre-computed {@link DailyReport} so the renderer stays a pure function
     * of plain data (Consolidated Daily Report feature). The aggregation itself
     * lives in {@link DailyReport#build}.
     *
     * @param report the fully-computed consolidated report for a single day
     */
    public record ConsolidatedReport(DailyReport report) {
    }
}
