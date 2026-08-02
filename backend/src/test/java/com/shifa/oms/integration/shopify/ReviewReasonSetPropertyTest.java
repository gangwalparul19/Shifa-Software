package com.shifa.oms.integration.shopify;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.integration.shopify.dto.ReviewQueueRow;
import com.shifa.oms.order.OrderCodeGenerator;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: shopify-quikshipx-order-sync, Property 9: review reasons form a set.
 *
 * <p>For any combination of defects in a Shopify payload, applied any number of times, the
 * reasons stored against the resulting order equal the distinct set of those defects, one row
 * each, and the review-queue row lists exactly that set alongside the Shopify order number.
 *
 * <p>The "one row each" half matters because the queue is the admin's to-do list: a reason
 * duplicated by a retried webhook would make the same problem look like several, and an order
 * would appear to need more work than it does.
 *
 * <p>Exercises the real {@link ShopifyOrderWriter} and {@link ReviewQueueService} over
 * in-memory repositories, so it also pins the mapping decisions those two make — an order is
 * always created, never rejected, whatever is wrong with the payload.
 *
 * <p>Validates: Requirements 3.4, 3.5, 3.7, 3.9, 3.11, 3.12
 */
class ReviewReasonSetPropertyTest {

    private static final String KNOWN_SKU = "SHR-ASH-60";

    @Property(tries = 300)
    void theStoredReasonsEqualTheDistinctDefectsHoweverManyTimesIngestionRuns(
            @ForAll("defects") Set<ReviewReason> defects,
            @ForAll @IntRange(min = 1, max = 4) int attempts) {

        Fixture fixture = new Fixture();
        ShopifyOrderModel model = modelWith(defects);

        Long orderId = null;
        for (int i = 0; i < attempts; i++) {
            ShopifyOrderWriter.CreateResult result = fixture.writer.resolveOrCreate(model);
            if (orderId == null) {
                orderId = result.orderId();
            }
            // Re-processing resolves the SAME order rather than creating another (Req 3.10).
            assertThat(result.orderId()).isEqualTo(orderId);
            assertThat(result.created()).isEqualTo(i == 0);
        }

        assertThat(fixture.orders).hasSize(1);
        // Exactly one row per distinct reason, no matter how many attempts ran.
        assertThat(fixture.reasons.values().stream()
                .map(OrderReviewReason::getReason).toList())
                .containsExactlyInAnyOrderElementsOf(defects);
        assertThat(fixture.reasons).hasSize(defects.size());

        List<ReviewQueueRow> queue = fixture.reviewQueue.list();
        if (defects.isEmpty()) {
            assertThat(queue).isEmpty();
            return;
        }
        assertThat(queue).hasSize(1);
        ReviewQueueRow row = queue.get(0);
        // The admin reconciles against the store, so the Shopify number must be present.
        assertThat(row.shopifyOrderNumber()).isEqualTo(model.shopifyOrderNumber());
        assertThat(row.shopifyOrderId()).isEqualTo(model.shopifyOrderId());
        assertThat(row.reasons().stream().map(ReviewQueueRow.Reason::reason).toList())
                .containsExactlyInAnyOrderElementsOf(
                        defects.stream().map(Enum::name).toList());
        // Every listed reason explains itself, so the queue is actionable.
        assertThat(row.reasons()).allSatisfy(reason -> {
            assertThat(reason.description()).isNotBlank();
            assertThat(reason.detail()).isNotBlank();
        });
    }

    @Property(tries = 200)
    void anOrderIsAlwaysCreatedWhateverIsWrongWithThePayload(
            @ForAll("defects") Set<ReviewReason> defects) {

        Fixture fixture = new Fixture();

        ShopifyOrderWriter.CreateResult result = fixture.writer.resolveOrCreate(modelWith(defects));

        // Losing an order the store has already taken is the worst possible outcome, so
        // every defect degrades into a flag rather than a rejection.
        assertThat(result.created()).isTrue();
        OrderEntity order = fixture.orders.get(result.orderId());
        assertThat(order.getSource()).isEqualTo(OrderSource.SHOPIFY_API);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        // Exactly one creation row, with no source status (Req 4.1).
        assertThat(order.getStatusHistory()).hasSize(1);
        assertThat(order.getStatusHistory().get(0).getFromStatus()).isNull();

        // NOT NULL columns always hold a storable value, never null (Req 3.5, 3.11).
        assertThat(order.getCustomerName()).isNotNull().isNotBlank();
        assertThat(order.getCustomerMobile()).isNotNull();
        assertThat(order.getAddressLine()).isNotNull();
        assertThat(order.getCity()).isNotNull();
        assertThat(order.getState()).isNotNull();
        assertThat(order.getPostalCode()).isNotNull();
        assertThat(order.getCustomerMobile().length()).isLessThanOrEqualTo(10);
        assertThat(order.getPostalCode().length()).isLessThanOrEqualTo(6);
    }

    @Test
    void theShopifyTotalIsPersistedVerbatimEvenWhenTheLinesDisagree() {
        Fixture fixture = new Fixture();
        ShopifyOrderModel model = new ShopifyOrderModel(
                "555", "#555", "Asha Kumar", "+91 98123 45678", "asha@example.com",
                new ShopifyOrderModel.Address("12 MG Road", "Pune", "Maharashtra", "411001", "India"),
                List.of(new ShopifyOrderModel.LineItem(KNOWN_SKU, "Ashwagandha", 2, new BigDecimal("499.00"))),
                new BigDecimal("1250.00"), "INR", "paid");

        ShopifyOrderWriter.CreateResult result = fixture.writer.resolveOrCreate(model);

        OrderEntity order = fixture.orders.get(result.orderId());
        // 1250 != 2 x 499. Shifa keeps Shopify's number so reports reconcile with the
        // store, and flags the discrepancy rather than silently "fixing" it (Req 3.6, 3.7).
        assertThat(order.getTotalAmount()).isEqualByComparingTo("1250.00");
        assertThat(result.reasons()).contains(ReviewReason.TOTAL_MISMATCH);
    }

    @Test
    void aMatchedSkuLinksTheProductAndAnUnmatchedOneStillRecordsTheLine() {
        Fixture fixture = new Fixture();
        ShopifyOrderModel model = new ShopifyOrderModel(
                "556", "#556", "Asha", "9812345678", null,
                new ShopifyOrderModel.Address("12 MG Road", "Pune", "Maharashtra", "411001", "India"),
                List.of(
                        new ShopifyOrderModel.LineItem("  shr-ash-60 ", "Ashwagandha", 1, new BigDecimal("500.00")),
                        new ShopifyOrderModel.LineItem("UNKNOWN-1", "Mystery item", 3, new BigDecimal("100.00"))),
                new BigDecimal("800.00"), "INR", "pending");

        ShopifyOrderWriter.CreateResult result = fixture.writer.resolveOrCreate(model);

        OrderEntity order = fixture.orders.get(result.orderId());
        assertThat(order.getLineItems()).hasSize(2);
        // Trimmed, case-insensitive match links the product and snapshots its HSN/GST.
        assertThat(order.getLineItems().get(0).getProductId()).isEqualTo(7L);
        assertThat(order.getLineItems().get(0).getHsnCode()).isEqualTo("30049011");
        // The unmatched line is kept by name, quantity and amount, with no product link.
        assertThat(order.getLineItems().get(1).getProductId()).isNull();
        assertThat(order.getLineItems().get(1).getProductName()).isEqualTo("Mystery item");
        assertThat(order.getLineItems().get(1).getQuantity()).isEqualTo(3);
        assertThat(order.getLineItems().get(1).getLineTotal()).isEqualByComparingTo("300.00");
        assertThat(result.reasons()).containsExactly(ReviewReason.UNMAPPED_SKU);

        // financial_status "pending" means the buyer has not paid, so this is COD.
        assertThat(order.getAmountReceived()).isEqualByComparingTo("0.00");
        assertThat(order.getCodAmount()).isEqualByComparingTo("800.00");
    }

    @Test
    void aPaidShopifyOrderCarriesNoScreenshotAndNoVerificationRequirement() {
        Fixture fixture = new Fixture();

        ShopifyOrderWriter.CreateResult result = fixture.writer.resolveOrCreate(modelWith(Set.of()));

        OrderEntity order = fixture.orders.get(result.orderId());
        assertThat(order.getAmountReceived()).isEqualByComparingTo(order.getTotalAmount());
        assertThat(order.getPayments()).hasSize(1);
        // Shopify collected the money through its own gateway, so there is nothing for the
        // payment verifier to inspect and the order must not sit in their queue.
        assertThat(order.getPaymentScreenshotKey()).isNull();
        assertThat(order.getPaymentVerificationStatus()).isNull();
    }

    // ------------------------------------------------------------------
    // Generators and fixtures
    // ------------------------------------------------------------------

    @Provide
    Arbitrary<Set<ReviewReason>> defects() {
        // Every subset, including the empty one (a clean order) and the full one.
        // Deliberately not EnumSet.copyOf: it rejects an empty non-EnumSet collection.
        return Arbitraries.of(ReviewReason.values())
                .set().ofMinSize(0).ofMaxSize(ReviewReason.values().length);
    }

    /** A payload exhibiting exactly the requested defects and no others. */
    private static ShopifyOrderModel modelWith(Set<ReviewReason> defects) {
        String contact = defects.contains(ReviewReason.MISSING_CONTACT)
                ? "not a phone" : "+91 98123 45678";
        ShopifyOrderModel.Address address = defects.contains(ReviewReason.INCOMPLETE_ADDRESS)
                ? new ShopifyOrderModel.Address("12 MG Road", "", "Maharashtra", "411001", "India")
                : new ShopifyOrderModel.Address("12 MG Road", "Pune", "Maharashtra", "411001", "India");
        String sku = defects.contains(ReviewReason.UNMAPPED_SKU) ? "NO-SUCH-SKU" : KNOWN_SKU;
        // One line of 500.00; the total agrees unless a mismatch was requested.
        BigDecimal total = defects.contains(ReviewReason.TOTAL_MISMATCH)
                ? new BigDecimal("900.00") : new BigDecimal("500.00");

        return new ShopifyOrderModel(
                "1042", "#1042", "Asha Kumar", contact, "asha@example.com", address,
                List.of(new ShopifyOrderModel.LineItem(sku, "Ashwagandha", 1, new BigDecimal("500.00"))),
                total, "INR", "paid");
    }

    /**
     * The writer and the queue over in-memory repositories.
     *
     * <p>Reflective proxies rather than mocks: Java 25's Mockito cannot mock concrete
     * classes, and a Spring Data interface has too large an inherited surface to implement
     * by hand. Any method the production code calls that is not modelled here throws, so a
     * gap in the double is a loud failure rather than a silent null.
     */
    private static final class Fixture {

        private final Map<Long, OrderEntity> orders = new LinkedHashMap<>();
        private final Map<String, OrderReviewReason> reasons = new LinkedHashMap<>();
        private final AtomicLong orderIds = new AtomicLong();
        private final AtomicLong reasonIds = new AtomicLong();
        private final List<Product> catalogue = List.of(product(7L, KNOWN_SKU, "Ashwagandha"));

        private final ShopifyOrderWriter writer;
        private final ReviewQueueService reviewQueue;

        Fixture() {
            OrderRepository orderRepository = orderRepository();
            OrderReviewReasonRepository reasonRepository = reasonRepository();
            this.writer = new ShopifyOrderWriter(
                    orderRepository,
                    productRepository(),
                    reasonRepository,
                    new OrderCodeGenerator(),
                    new OrderWorkflowService(new SilentAudit()),
                    new SilentAudit());
            this.reviewQueue = new ReviewQueueService(reasonRepository, orderRepository);
        }

        private static Product product(Long id, String sku, String name) {
            Product product = new Product(sku, name, "desc",
                    new BigDecimal("600.00"), new BigDecimal("500.00"), ProductVisibility.PUBLISHED);
            ReflectionTestUtils.setField(product, "id", id);
            product.setHsnCode("30049011");
            product.setGstRate(new BigDecimal("12.00"));
            return product;
        }

        private OrderRepository orderRepository() {
            return (OrderRepository) Proxy.newProxyInstance(
                    OrderRepository.class.getClassLoader(),
                    new Class<?>[]{OrderRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findByShopifyOrderId" -> orders.values().stream()
                                .filter(o -> args[0].equals(o.getShopifyOrderId()))
                                .findFirst();
                        case "existsByOrderCode" -> orders.values().stream()
                                .anyMatch(o -> args[0].equals(o.getOrderCode()));
                        case "findById" -> Optional.ofNullable(orders.get(args[0]));
                        case "findAllById" -> {
                            List<OrderEntity> found = new ArrayList<>();
                            for (Object id : (Iterable<?>) args[0]) {
                                OrderEntity order = orders.get(id);
                                if (order != null) {
                                    found.add(order);
                                }
                            }
                            yield found;
                        }
                        case "save", "saveAndFlush" -> persistOrder((OrderEntity) args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private OrderEntity persistOrder(OrderEntity order) {
            if (order.getId() == null) {
                ReflectionTestUtils.setField(order, "id", orderIds.incrementAndGet());
            }
            orders.put(order.getId(), order);
            return order;
        }

        private ProductRepository productRepository() {
            return (ProductRepository) Proxy.newProxyInstance(
                    ProductRepository.class.getClassLoader(),
                    new Class<?>[]{ProductRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findAll" -> catalogue;
                        case "findById" -> catalogue.stream()
                                .filter(p -> args[0].equals(p.getId())).findFirst();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private OrderReviewReasonRepository reasonRepository() {
            return (OrderReviewReasonRepository) Proxy.newProxyInstance(
                    OrderReviewReasonRepository.class.getClassLoader(),
                    new Class<?>[]{OrderReviewReasonRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        // Models UNIQUE(order_id, reason).
                        case "existsByOrderIdAndReason" -> reasons.containsKey(args[0] + "|" + args[1]);
                        case "save", "saveAndFlush" -> persistReason((OrderReviewReason) args[0]);
                        case "findByOrderIdOrderByIdAsc" -> reasons.values().stream()
                                .filter(r -> args[0].equals(r.getOrderId()))
                                .sorted(Comparator.comparing(OrderReviewReason::getId))
                                .toList();
                        case "findByOrderIdInOrderByOrderIdAscIdAsc" -> {
                            List<Object> ids = new ArrayList<>();
                            ((Iterable<?>) args[0]).forEach(ids::add);
                            yield reasons.values().stream()
                                    .filter(r -> ids.contains(r.getOrderId()))
                                    .sorted(Comparator.comparing(OrderReviewReason::getOrderId)
                                            .thenComparing(OrderReviewReason::getId))
                                    .toList();
                        }
                        case "findDistinctOrderIds" -> reasons.values().stream()
                                .map(OrderReviewReason::getOrderId)
                                .distinct()
                                .sorted(Comparator.reverseOrder())
                                .toList();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private OrderReviewReason persistReason(OrderReviewReason reason) {
            if (reason.getId() == null) {
                ReflectionTestUtils.setField(reason, "id", reasonIds.incrementAndGet());
            }
            reasons.put(reason.getOrderId() + "|" + reason.getReason(), reason);
            return reason;
        }
    }

    /** Audit is best-effort and never-throwing; this double simply says nothing. */
    private static class SilentAudit extends AuditService {

        SilentAudit() {
            super(null, null);
        }

        @Override
        public AuditEvent record(String action, String entityType, String entityId, String summary) {
            return null;
        }

        @Override
        public AuditEvent record(Long actorUserId, String actorUsername, String action,
                                 String entityType, String entityId, String summary) {
            return null;
        }
    }
}
