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
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for bulk internal-label output fan-out.
 *
 * Feature: shifa-herbal-remedies, Property 19: Bulk label output has one label
 * per requested order. For ANY non-empty set of eligible orders, the produced
 * bulk document contains exactly one label block per requested order.
 *
 * <p>Tested at the content/model level ({@link LabelContentBuilder#buildBulk}),
 * so it does not require parsing PDF bytes.
 *
 * Validates: Requirements 10.4, 12.5
 */
class BulkLabelOutputPropertyTest {

    private final LabelContentBuilder builder = new LabelContentBuilder();

    // Feature: shifa-herbal-remedies, Property 19: Bulk label output has one label per requested order
    @Property(tries = 200)
    void bulkProducesExactlyOneBlockPerRequestedOrder(@ForAll("orderLists") List<OrderEntity> orders) {
        List<InternalLabelContent> blocks = builder.buildBulk(orders);

        // Exactly one block per requested order, in the requested order.
        assertThat(blocks).hasSameSizeAs(orders);
        for (int i = 0; i < orders.size(); i++) {
            assertThat(blocks.get(i).orderCode()).isEqualTo(orders.get(i).getOrderCode());
        }

        // Each requested order code appears exactly as many times as it was requested.
        Map<String, Long> requested = orders.stream()
                .collect(Collectors.groupingBy(OrderEntity::getOrderCode, Collectors.counting()));
        Map<String, Long> produced = blocks.stream()
                .collect(Collectors.groupingBy(InternalLabelContent::orderCode, Collectors.counting()));
        assertThat(produced).isEqualTo(requested);
    }

    // --- Generators ---------------------------------------------------------

    @Provide
    Arbitrary<List<OrderEntity>> orderLists() {
        return orders().list().ofMinSize(1).ofMaxSize(8);
    }

    private Arbitrary<OrderEntity> orders() {
        Arbitrary<String> code = Arbitraries.strings().withCharRange('0', '9')
                .ofLength(6).map(s -> "SHR-" + s);
        Arbitrary<PaymentStatus> status = Arbitraries.of(PaymentStatus.values());
        Arbitrary<List<OrderLineItem>> lines = lineItems().list().ofMinSize(1).ofMaxSize(4);
        return Combinators.combine(code, status, lines).as(this::buildOrder);
    }

    private Arbitrary<OrderLineItem> lineItems() {
        Arbitrary<String> productName = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(16);
        Arbitrary<Integer> quantity = Arbitraries.integers().between(1, 999);
        Arbitrary<Long> rateRupees = Arbitraries.longs().between(1, 5_000);
        return Combinators.combine(productName, quantity, rateRupees).as((n, q, r) -> {
            BigDecimal rate = new BigDecimal(r).setScale(2);
            return new OrderLineItem(null, n, q, rate, rate.multiply(BigDecimal.valueOf(q)));
        });
    }

    private OrderEntity buildOrder(String code, PaymentStatus status, List<OrderLineItem> lines) {
        OrderEntity order = new OrderEntity(
                code, OrderSource.SALESPERSON, 7L,
                "Customer", "9812345678", "1 Main Road", "Pune", "Maharashtra", "411001");
        for (OrderLineItem line : lines) {
            order.addLineItem(line);
        }
        BigDecimal total = lines.stream()
                .map(OrderLineItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cod = status == PaymentStatus.FULLY_PAID ? BigDecimal.ZERO.setScale(2) : total;
        BigDecimal received = total.subtract(cod);
        order.applyAmounts(total, received, total.subtract(received), cod, status);
        return order;
    }
}
