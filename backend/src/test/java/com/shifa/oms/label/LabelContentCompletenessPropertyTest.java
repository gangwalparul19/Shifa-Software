package com.shifa.oms.label;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for internal company label content completeness.
 *
 * Feature: shifa-herbal-remedies, Property 18: Label content completeness. For
 * ANY order, the generated internal label content model contains the order
 * identifier, a barcode value equal to the orderCode, the customer details, and
 * every line item; and the COD_Amount is present IF AND ONLY IF the order is COD
 * or Partially_Paid.
 *
 * Validates: Requirements 10.1, 10.2, 12.3
 */
class LabelContentCompletenessPropertyTest {

    private final LabelContentBuilder builder = new LabelContentBuilder();

    // Feature: shifa-herbal-remedies, Property 18: Label content completeness
    @Property(tries = 200)
    void internalLabelContainsAllOrderDetailsAndCodIffCodOrPartial(@ForAll("orders") OrderEntity order) {
        InternalLabelContent content = builder.buildInternal(order);

        // Order identifier + barcode value equal to the order code (Req 10.1).
        assertThat(content.orderCode()).isEqualTo(order.getOrderCode());
        assertThat(content.barcodeValue()).isEqualTo(order.getOrderCode());

        // Customer details (Req 10.1).
        assertThat(content.customerName()).isEqualTo(order.getCustomerName());
        assertThat(content.customerMobile()).isEqualTo(order.getCustomerMobile());
        assertThat(content.addressLine()).isEqualTo(order.getAddressLine());
        assertThat(content.city()).isEqualTo(order.getCity());
        assertThat(content.state()).isEqualTo(order.getState());
        assertThat(content.postalCode()).isEqualTo(order.getPostalCode());

        // Every line item is present, name + quantity, in order (Req 10.1).
        assertThat(content.lineItems()).hasSameSizeAs(order.getLineItems());
        for (int i = 0; i < order.getLineItems().size(); i++) {
            OrderLineItem source = order.getLineItems().get(i);
            InternalLabelContent.LabelLineItem line = content.lineItems().get(i);
            assertThat(line.productName()).isEqualTo(source.getProductName());
            assertThat(line.quantity()).isEqualTo(source.getQuantity());
        }

        // COD_Amount present IFF payment status is COD or Partially_Paid (Req 10.2).
        boolean expectedCod = order.getPaymentStatus() == PaymentStatus.COD
                || order.getPaymentStatus() == PaymentStatus.PARTIALLY_PAID;
        assertThat(content.codApplicable()).isEqualTo(expectedCod);
        if (expectedCod) {
            assertThat(content.codAmount()).isNotNull();
            assertThat(content.codAmount()).isEqualByComparingTo(order.getCodAmount());
        } else {
            assertThat(content.codAmount()).isNull();
        }
    }

    // --- Generators ---------------------------------------------------------

    @Provide
    Arbitrary<OrderEntity> orders() {
        Arbitrary<String> code = Arbitraries.strings().withCharRange('0', '9')
                .ofLength(6).map(s -> "SHR-" + s);
        Arbitrary<String> name = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> mobile = Arbitraries.strings().withCharRange('0', '9').ofLength(10);
        Arbitrary<PaymentStatus> status = Arbitraries.of(PaymentStatus.values());
        Arbitrary<List<OrderLineItem>> lines = lineItems().list().ofMinSize(1).ofMaxSize(6);
        Arbitrary<Long> codRupees = Arbitraries.longs().between(1, 100_000);

        return Combinators.combine(code, name, mobile, status, lines, codRupees)
                .as(this::buildOrder);
    }

    private Arbitrary<OrderLineItem> lineItems() {
        Arbitrary<String> productName = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(24);
        Arbitrary<Integer> quantity = Arbitraries.integers().between(1, 999);
        Arbitrary<Long> rateRupees = Arbitraries.longs().between(1, 10_000);
        return Combinators.combine(productName, quantity, rateRupees).as((n, q, r) -> {
            BigDecimal rate = new BigDecimal(r).setScale(2);
            BigDecimal lineTotal = rate.multiply(BigDecimal.valueOf(q));
            return new OrderLineItem(null, n, q, rate, lineTotal);
        });
    }

    private OrderEntity buildOrder(String code, String name, String mobile,
                                   PaymentStatus status, List<OrderLineItem> lines, Long codRupees) {
        OrderEntity order = new OrderEntity(
                code, OrderSource.SALESPERSON, 7L,
                name, mobile, name + " Street", "Pune", "Maharashtra", "411001");
        for (OrderLineItem line : lines) {
            order.addLineItem(line);
        }
        BigDecimal total = lines.stream()
                .map(OrderLineItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cod;
        BigDecimal received;
        switch (status) {
            case COD -> {
                cod = total;
                received = BigDecimal.ZERO.setScale(2);
            }
            case PARTIALLY_PAID -> {
                // Some COD remains; keep it within the total.
                cod = new BigDecimal(codRupees).setScale(2).min(total);
                if (cod.signum() == 0) {
                    cod = new BigDecimal("1.00");
                }
                received = total.subtract(cod);
            }
            default -> { // FULLY_PAID
                cod = BigDecimal.ZERO.setScale(2);
                received = total;
            }
        }
        order.applyAmounts(total, received, total.subtract(received), cod, status);
        return order;
    }
}
