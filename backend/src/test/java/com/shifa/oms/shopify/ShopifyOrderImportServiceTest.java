package com.shifa.oms.shopify;

import com.shifa.oms.order.Actor;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderCodeGenerator;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.shopify.dto.ShopifyOrderPayload;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShopifyOrderImportService}: mapping a Shopify payload to a
 * tagged OMS order, deriving payment from {@code financial_status}, SKU-matching
 * line items, best-effort mobile/address normalisation, auto-approval, and
 * idempotency on a redelivered webhook.
 *
 * <p>Java 25 can't Mockito-mock concrete classes, so the workflow/label/outbox
 * collaborators are lightweight <em>recording subclasses</em>: the workflow one
 * simulates the APPROVED transition, the label one simulates label generation
 * (APPROVED -> LABEL_GENERATED), and the outbox one records the published events.
 * The order MAPPING is asserted on the FIRST {@code save(...)} (the order in its
 * pre-approval state); the auto-approval outcome is asserted via the recorded
 * transition + final status.
 */
class ShopifyOrderImportServiceTest {

    private OrderRepository orderRepository;
    private ProductRepository productRepository;
    private RecordingWorkflow workflow;
    private RecordingLabels labels;
    private RecordingOutbox outbox;
    private ShopifyOrderImportService service;

    /** Records the APPROVED transition and simulates it on the entity. */
    private static final class RecordingWorkflow extends OrderWorkflowService {
        OrderStatus transitioned;
        Actor actor;

        RecordingWorkflow() {
            // OrderWorkflowService requires a non-null AuditService; a null-repo one
            // is fine because our override never routes through the audit path.
            super(new com.shifa.oms.audit.AuditService(null, null));
        }

        @Override
        public com.shifa.oms.statemachine.StatusHistoryEntry applyTransition(
                OrderEntity order, OrderStatus target, Actor actor) {
            this.transitioned = target;
            this.actor = actor;
            order.setOrderStatus(target);
            return null;
        }
    }

    /** Simulates label generation moving APPROVED -> LABEL_GENERATED. */
    private static final class RecordingLabels extends LabelService {
        boolean generated;

        RecordingLabels(OrderRepository orderRepository) {
            super(orderRepository, null);
        }

        @Override
        public String generateInternalLabelOnApproval(OrderEntity order, String actor) {
            this.generated = true;
            order.setOrderStatus(OrderStatus.LABEL_GENERATED);
            return "labels/internal/" + order.getOrderCode() + ".pdf";
        }
    }

    /** Records outbox publications without a repository. */
    private static final class RecordingOutbox extends OutboxEventPublisher {
        int ledgerPosts;

        RecordingOutbox() {
            super((com.shifa.oms.platform.outbox.OutboxEventRepository) null);
        }

        @Override
        public OutboxEvent publishLedgerPost(String sourceType, Long sourceId) {
            ledgerPosts++;
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        productRepository = mock(ProductRepository.class);
        workflow = new RecordingWorkflow();
        labels = new RecordingLabels(orderRepository);
        outbox = new RecordingOutbox();
        // Null QuikShipXProperties => the QuikShipX publish branch is skipped.
        // Null OrderShipmentRepository => the stuck/recover list treats every order
        // as having no AWB yet (so these status-based tests keep their old meaning).
        service = new ShopifyOrderImportService(
                orderRepository, productRepository, new OrderCodeGenerator(),
                workflow, labels, outbox, null, null);

        when(orderRepository.existsByOrderCode(any())).thenReturn(false);
        when(orderRepository.findByShopifyOrderId(any())).thenReturn(Optional.empty());
        when(productRepository.findBySku(any())).thenReturn(Optional.empty());
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void importsPaidShopifyOrderTaggedFullyPaidAndAutoApprovesToLabelGenerated() {
        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                987654321L, 1001L, "#1001", "buyer@example.com", "+91 98765 43210",
                "gift wrap please", "INR", new BigDecimal("2200.00"), null, null, "paid",
                List.of(new ShopifyOrderPayload.LineItem("SHIFA-001", "Gut Cleanser", null, 1, new BigDecimal("2200.00"))),
                new ShopifyOrderPayload.Customer("Ashmita", "Goyal", "buyer@example.com", null),
                new ShopifyOrderPayload.Address("Ashmita Goyal", null, null, "9876543210",
                        "Bholaram Ustad Marg", null, "Indore", "Madhya Pradesh", "452001", "India"),
                null);

        ShopifyOrderImportService.ImportResult result = service.importOrder(payload);

        assertThat(result.created()).isTrue();
        assertThat(result.orderCode()).startsWith("SHR-");

        OrderEntity saved = firstSaved();
        assertThat(saved.getSource()).isEqualTo(OrderSource.SHOPIFY);
        assertThat(saved.getLeadSource()).isEqualTo(LeadSource.SHOPIFY);
        assertThat(saved.getShopifyOrderId()).isEqualTo("987654321");
        assertThat(saved.getCustomerName()).isEqualTo("Ashmita Goyal");
        assertThat(saved.getCustomerMobile()).isEqualTo("9876543210");
        assertThat(saved.getCity()).isEqualTo("Indore");
        assertThat(saved.getPostalCode()).isEqualTo("452001");
        assertThat(saved.getCreatedBy()).isNull();
        assertThat(saved.getNotes()).contains("Shopify");
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.FULLY_PAID);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("2200.00");
        assertThat(saved.getAmountReceived()).isEqualByComparingTo("2200.00");
        assertThat(saved.getCodAmount()).isEqualByComparingTo("0.00");
        assertThat(saved.getLineItems()).hasSize(1);

        // Auto-approval: transitioned to APPROVED (ADMIN-role SHOPIFY actor),
        // label generated -> LABEL_GENERATED, ledger post enqueued.
        assertThat(workflow.transitioned).isEqualTo(OrderStatus.APPROVED);
        assertThat(workflow.actor.role()).isEqualTo(com.shifa.oms.auth.Role.ADMIN);
        assertThat(labels.generated).isTrue();
        assertThat(saved.getOrderStatus()).isEqualTo(OrderStatus.LABEL_GENERATED);
        assertThat(outbox.ledgerPosts).isEqualTo(1);
    }

    @Test
    void discountedShopifyOrderRecordsTheGapAsDiscountSoAmountsReconcile() {
        // Mirrors the real stuck order SHR-20260926-D6YN: gross line subtotal
        // 999.00 + 2*1099.99 = 3198.98, but Shopify charged a discounted total of
        // 2979.00 (a 219.98 checkout discount). The importer must record that gap as
        // the order-level discount so subtotal - discount == total — otherwise
        // QuikShipX rejects create with "Calculated Products and Order Amount Not
        // Matched".
        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                25445L, 25445L, "#25445", null, "7022097570", null, "INR",
                new BigDecimal("2979.00"), null, null, "paid",
                List.of(
                        new ShopifyOrderPayload.LineItem(null, "Power Gold", null, 1, new BigDecimal("999.00")),
                        new ShopifyOrderPayload.LineItem(null, "Height Heal", null, 2, new BigDecimal("1099.99"))),
                null,
                new ShopifyOrderPayload.Address("Ummer Shareef", null, null, "7022097570",
                        "1 St", null, "Bengaluru", "Karnataka", "560001", "India"),
                null);

        service.importOrder(payload);

        OrderEntity saved = firstSaved();
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("2979.00");
        assertThat(saved.getDiscountAmount()).isEqualByComparingTo("219.98");

        // The reconciliation QuikShipX validates: Σ(lineTotals) - discount == total.
        BigDecimal subtotal = saved.getLineItems().stream()
                .map(OrderLineItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(subtotal).isEqualByComparingTo("3198.98");
        assertThat(subtotal.subtract(saved.getDiscountAmount())).isEqualByComparingTo(saved.getTotalAmount());
    }

    @Test
    void undiscountedShopifyOrderRecordsNoDiscount() {
        // Line subtotal == total: no discount to record.
        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                25446L, 25446L, "#25446", null, "7022097570", null, "INR",
                new BigDecimal("999.00"), null, null, "paid",
                List.of(new ShopifyOrderPayload.LineItem(null, "Power Gold", null, 1, new BigDecimal("999.00"))),
                null,
                new ShopifyOrderPayload.Address("Buyer", null, null, "7022097570",
                        "1 St", null, "Delhi", "Delhi", "110001", "India"),
                null);

        service.importOrder(payload);

        assertThat(firstSaved().getDiscountAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void unpaidShopifyOrderIsTreatedAsCollectOnDelivery() {
        ShopifyOrderPayload payload = orderWithStatus("pending", new BigDecimal("1200.00"));

        service.importOrder(payload);

        OrderEntity saved = firstSaved();
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.COD);
        assertThat(saved.getAmountReceived()).isEqualByComparingTo("0.00");
        assertThat(saved.getCodAmount()).isEqualByComparingTo("1200.00");
        assertThat(saved.getCustomerOutstanding()).isEqualByComparingTo("1200.00");
    }

    @Test
    void partiallyPaidShopifyOrderRecordsRealReceivedAndBalanceAsCod() {
        // total 1000, outstanding 400 => 600 already paid on Shopify, 400 to collect.
        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                25422L, 25422L, "#25422", null, "9998887776", null, "INR",
                new BigDecimal("1000.00"), null, new BigDecimal("400.00"), "partially_paid",
                List.of(new ShopifyOrderPayload.LineItem(null, "Item", null, 1, new BigDecimal("1000.00"))),
                null,
                new ShopifyOrderPayload.Address("Buyer", null, null, "9998887776",
                        "1 St", null, "Delhi", "Delhi", "110001", "India"),
                null);

        service.importOrder(payload);

        OrderEntity saved = firstSaved();
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(saved.getAmountReceived()).isEqualByComparingTo("600.00");
        assertThat(saved.getRemainingAmount()).isEqualByComparingTo("400.00");
        assertThat(saved.getCodAmount()).isEqualByComparingTo("400.00");
        assertThat(saved.getCustomerOutstanding()).isEqualByComparingTo("400.00");
    }

    @Test
    void partiallyPaidWithoutOutstandingAmountFallsBackToFullCod() {
        // financial_status says part-paid but no amount given => we can't split, so
        // the whole balance is treated as still-to-collect (safe default).
        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                26000L, 26000L, "#26000", null, "9998887776", null, "INR",
                new BigDecimal("500.00"), null, null, "partially_paid",
                List.of(new ShopifyOrderPayload.LineItem(null, "Item", null, 1, new BigDecimal("500.00"))),
                null, null, null);

        service.importOrder(payload);

        OrderEntity saved = firstSaved();
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.COD);
        assertThat(saved.getCodAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void paidWithZeroOutstandingIsFullyPaid() {
        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                27000L, 27000L, "#27000", null, "9998887776", null, "INR",
                new BigDecimal("800.00"), null, new BigDecimal("0.00"), "paid",
                List.of(new ShopifyOrderPayload.LineItem(null, "Item", null, 1, new BigDecimal("800.00"))),
                null, null, null);

        service.importOrder(payload);

        OrderEntity saved = firstSaved();
        assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.FULLY_PAID);
        assertThat(saved.getAmountReceived()).isEqualByComparingTo("800.00");
        assertThat(saved.getCodAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void matchesLineItemToCatalogueProductBySkuSnapshottingHsnAndGst() {
        Product product = new Product("SHIFA-007", "Immuno Booster", "d",
                new BigDecimal("999.00"), new BigDecimal("500.00"), ProductVisibility.PUBLISHED);
        product.setHsnCode("3004");
        product.setGstRate(new BigDecimal("12.00"));
        when(productRepository.findBySku("SHIFA-007")).thenReturn(Optional.of(product));

        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                5L, 5L, "#5", null, "9998887776", null, "INR",
                new BigDecimal("450.00"), null, null, "paid",
                List.of(new ShopifyOrderPayload.LineItem("SHIFA-007", "Immuno Booster (Shopify title)", null, 1, new BigDecimal("450.00"))),
                null,
                new ShopifyOrderPayload.Address("Buyer", null, null, "9998887776",
                        "1 St", null, "Delhi", "Delhi", "110001", "India"),
                null);

        service.importOrder(payload);

        OrderEntity saved = firstSaved();
        assertThat(saved.getLineItems()).hasSize(1);
        assertThat(saved.getLineItems().get(0).getProductName()).isEqualTo("Immuno Booster");
        assertThat(saved.getLineItems().get(0).getHsnCode()).isEqualTo("3004");
        assertThat(saved.getLineItems().get(0).getGstRate()).isEqualByComparingTo("12.00");
    }

    @Test
    void unmatchedLineItemKeepsShopifyTitleWithNoProductId() {
        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                6L, 6L, "#6", null, "9998887776", null, "INR",
                new BigDecimal("300.00"), null, null, "pending",
                List.of(new ShopifyOrderPayload.LineItem("UNKNOWN-SKU", "Mystery Tonic", null, 2, new BigDecimal("150.00"))),
                null, null, null);

        service.importOrder(payload);

        OrderEntity saved = firstSaved();
        assertThat(saved.getLineItems()).hasSize(1);
        assertThat(saved.getLineItems().get(0).getProductId()).isNull();
        assertThat(saved.getLineItems().get(0).getProductName()).isEqualTo("Mystery Tonic");
        assertThat(saved.getLineItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(saved.getLineItems().get(0).getLineTotal()).isEqualByComparingTo("300.00");
    }

    @Test
    void foreignPhoneWithCountryCodeIsNormalisedToLastTenDigits() {
        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                7L, 7L, "#7", null, "+91-98765-43210", null, "INR",
                new BigDecimal("100.00"), null, null, "paid",
                List.of(new ShopifyOrderPayload.LineItem(null, "Item", null, 1, new BigDecimal("100.00"))),
                null, null, null);

        service.importOrder(payload);

        assertThat(firstSaved().getCustomerMobile()).isEqualTo("9876543210");
    }

    @Test
    void missingPhoneFallsBackToPlaceholderMobile() {
        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                8L, 8L, "#8", null, null, null, "INR",
                new BigDecimal("100.00"), null, null, "paid",
                List.of(new ShopifyOrderPayload.LineItem(null, "Item", null, 1, new BigDecimal("100.00"))),
                null, null, null);

        service.importOrder(payload);

        assertThat(firstSaved().getCustomerMobile()).isEqualTo("0000000000");
    }

    @Test
    void internationalOrderStoresCountryAndLeavesStructuredPartsEmpty() {
        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                9L, 9L, "#9", null, "12025550100", null, "USD",
                new BigDecimal("100.00"), null, null, "paid",
                List.of(new ShopifyOrderPayload.LineItem(null, "Item", null, 1, new BigDecimal("100.00"))),
                null,
                new ShopifyOrderPayload.Address("US Buyer", null, null, "12025550100",
                        "500 5th Ave", "Apt 2", "New York", "NY", "10110", "United States"),
                null);

        service.importOrder(payload);

        OrderEntity saved = firstSaved();
        assertThat(saved.getCountry()).isEqualTo("United States");
        assertThat(saved.getCity()).isEmpty();
        assertThat(saved.getState()).isEmpty();
        assertThat(saved.getPostalCode()).isEmpty();
        assertThat(saved.getAddressLine()).contains("500 5th Ave");
    }

    @Test
    void redeliveredWebhookIsIdempotentAndDoesNotCreateADuplicate() {
        OrderEntity already = new OrderEntity(
                "SHR-20260101-AAAA", OrderSource.SHOPIFY, null, "Buyer", "9998887776",
                "1 St", "Delhi", "Delhi", "110001");
        when(orderRepository.findByShopifyOrderId("42")).thenReturn(Optional.of(already));

        ShopifyOrderPayload payload = new ShopifyOrderPayload(
                42L, 42L, "#42", null, "9998887776", null, "INR",
                new BigDecimal("100.00"), null, null, "paid",
                List.of(new ShopifyOrderPayload.LineItem(null, "Item", null, 1, new BigDecimal("100.00"))),
                null, null, null);

        ShopifyOrderImportService.ImportResult result = service.importOrder(payload);

        assertThat(result.created()).isFalse();
        assertThat(result.orderCode()).isEqualTo("SHR-20260101-AAAA");
        verify(orderRepository, never()).save(any(OrderEntity.class));
        assertThat(workflow.transitioned).isNull(); // no auto-approval on a duplicate
    }

    private static OrderEntity shopifyOrder(String code, OrderStatus status,
                                            com.shifa.oms.order.DeliveryMethod method) {
        OrderEntity o = new OrderEntity(code, OrderSource.SHOPIFY, null, "Buyer", "9998887776",
                "1 St", "Delhi", "Delhi", "110001");
        o.setOrderStatus(status);
        o.setDeliveryMethod(method);
        o.applyAmounts(new BigDecimal("500.00"), BigDecimal.ZERO, new BigDecimal("500.00"),
                new BigDecimal("500.00"), PaymentStatus.COD);
        return o;
    }

    @Test
    void recoverSkipsInHouseLabelGeneratedOrders() {
        when(orderRepository.findBySourceAndOrderStatusOrderByCreatedAtDesc(
                OrderSource.SHOPIFY, OrderStatus.PENDING_ADMIN_APPROVAL)).thenReturn(List.of());
        when(orderRepository.findBySourceAndOrderStatusOrderByCreatedAtDesc(
                OrderSource.SHOPIFY, OrderStatus.LABEL_GENERATED)).thenReturn(List.of(
                shopifyOrder("SHR-A", OrderStatus.LABEL_GENERATED, com.shifa.oms.order.DeliveryMethod.QUIKSHIPX),
                shopifyOrder("SHR-B", OrderStatus.LABEL_GENERATED, com.shifa.oms.order.DeliveryMethod.IN_HOUSE)));

        ShopifyOrderImportService.RecoverResult result = service.recoverStuckShopifyOrders();

        assertThat(result.republishedFromLabelGenerated()).isEqualTo(1);
        assertThat(result.approvedFromPending()).isZero();
    }

    @Test
    void stuckListFlagsInHouseOrdersAsNotRecoverable() {
        when(orderRepository.findBySourceAndOrderStatusOrderByCreatedAtDesc(
                OrderSource.SHOPIFY, OrderStatus.PENDING_ADMIN_APPROVAL)).thenReturn(List.of(
                shopifyOrder("SHR-P", OrderStatus.PENDING_ADMIN_APPROVAL, com.shifa.oms.order.DeliveryMethod.IN_HOUSE)));
        when(orderRepository.findBySourceAndOrderStatusOrderByCreatedAtDesc(
                OrderSource.SHOPIFY, OrderStatus.LABEL_GENERATED)).thenReturn(List.of(
                shopifyOrder("SHR-Q", OrderStatus.LABEL_GENERATED, com.shifa.oms.order.DeliveryMethod.QUIKSHIPX),
                shopifyOrder("SHR-H", OrderStatus.LABEL_GENERATED, com.shifa.oms.order.DeliveryMethod.IN_HOUSE)));

        List<ShopifyOrderImportService.StuckOrder> stuck = service.listStuckShopifyOrders();

        assertThat(stuck).hasSize(3);
        assertThat(stuck).filteredOn(s -> s.orderCode().equals("SHR-P")).singleElement()
                .satisfies(s -> assertThat(s.recoverable()).isTrue()); // pending always recoverable
        assertThat(stuck).filteredOn(s -> s.orderCode().equals("SHR-Q")).singleElement()
                .satisfies(s -> assertThat(s.recoverable()).isTrue());
        assertThat(stuck).filteredOn(s -> s.orderCode().equals("SHR-H")).singleElement()
                .satisfies(s -> {
                    assertThat(s.recoverable()).isFalse();
                    assertThat(s.inHouse()).isTrue();
                });
    }

    /** The entity passed to the FIRST save (the imported order before auto-approval). */
    private OrderEntity firstSaved() {
        org.mockito.ArgumentCaptor<OrderEntity> captor = org.mockito.ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(0);
    }

    private static ShopifyOrderPayload orderWithStatus(String financialStatus, BigDecimal total) {
        return new ShopifyOrderPayload(
                100L, 100L, "#100", null, "9998887776", null, "INR",
                total, null, null, financialStatus,
                List.of(new ShopifyOrderPayload.LineItem(null, "Item", null, 1, total)),
                null,
                new ShopifyOrderPayload.Address("Buyer", null, null, "9998887776",
                        "1 St", null, "Delhi", "Delhi", "110001", "India"),
                null);
    }
}
