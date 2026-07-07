package com.shifa.oms.reporting.domain;

import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A pure, persistence-free projection of an Order used by the
 * {@link ReportAggregator}. The reporting service builds one of these per order
 * (joining the order, its receivables, and its courier record) so all report
 * aggregation and windowing is exercised against plain values and can be
 * property-tested without a database.
 *
 * <p>Carries exactly the fields the salesperson-wise report needs (Req 20.3):
 * customer name/mobile, the product lines (name + quantity), the money fields,
 * payment/order status, COD settlement status, loss claim status, AWB, and the
 * order date, plus the salesperson id ({@code createdBy}), destination state,
 * and {@link LeadSource} used by the grouped reports (Req 16.1, 16.3).
 */
public record OrderReportRecord(
        Long orderId,
        String orderCode,
        LocalDate orderDate,
        Long salespersonId,
        String customerName,
        String customerMobile,
        String state,
        List<ProductLine> products,
        BigDecimal totalAmount,
        BigDecimal amountReceived,
        BigDecimal codAmount,
        PaymentStatus paymentStatus,
        OrderStatus orderStatus,
        String codSettlementStatus,
        String claimStatus,
        String awb,
        LeadSource leadSource) {

    public OrderReportRecord {
        products = products == null ? List.of() : List.copyOf(products);
        totalAmount = nz(totalAmount);
        amountReceived = nz(amountReceived);
        codAmount = nz(codAmount);
    }

    /**
     * Backwards-compatible constructor for callers that do not track the lead
     * source (the {@link LeadSource} defaults to {@code null}, reported as
     * {@code UNSPECIFIED} by the grouped reports).
     */
    public OrderReportRecord(
            Long orderId,
            String orderCode,
            LocalDate orderDate,
            Long salespersonId,
            String customerName,
            String customerMobile,
            String state,
            List<ProductLine> products,
            BigDecimal totalAmount,
            BigDecimal amountReceived,
            BigDecimal codAmount,
            PaymentStatus paymentStatus,
            OrderStatus orderStatus,
            String codSettlementStatus,
            String claimStatus,
            String awb) {
        this(orderId, orderCode, orderDate, salespersonId, customerName, customerMobile, state,
                products, totalAmount, amountReceived, codAmount, paymentStatus, orderStatus,
                codSettlementStatus, claimStatus, awb, null);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * A single product line on an order: product name, quantity, the applied
     * unit rate, and the line total ({@code rate × quantity}). Rate and line
     * total back the Vyapar billing export and exact product-wise sales; the
     * salesperson report only surfaces name + quantity (Req 20.3).
     */
    public record ProductLine(String productName, int quantity, BigDecimal rate, BigDecimal lineTotal) {
        public ProductLine {
            Objects.requireNonNull(productName, "productName");
            rate = rate == null ? BigDecimal.ZERO : rate;
            lineTotal = lineTotal == null
                    ? rate.multiply(BigDecimal.valueOf(quantity))
                    : lineTotal;
        }

        /** Convenience constructor for callers that only track name + quantity. */
        public ProductLine(String productName, int quantity) {
            this(productName, quantity, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /** A human-readable "product1 x2, product2 x1" summary of the product lines. */
    public String productSummary() {
        StringBuilder sb = new StringBuilder();
        for (ProductLine p : products) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.productName()).append(" x").append(p.quantity());
        }
        return sb.toString();
    }
}
