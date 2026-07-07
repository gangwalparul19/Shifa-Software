package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.coupon.Coupon;
import com.shifa.oms.coupon.CouponRepository;
import com.shifa.oms.coupon.CouponService;
import com.shifa.oms.coupon.domain.CouponType;
import com.shifa.oms.courier.CourierCompany;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.courier.TrackingService;
import com.shifa.oms.notification.OrderConfirmationNotifier;
import com.shifa.oms.notification.WhatsAppMessageFactory;
import com.shifa.oms.notification.WhatsAppNotificationPublisher;
import com.shifa.oms.notification.WhatsAppTemplateRegistry;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.CheckoutRequest;
import com.shifa.oms.order.dto.CheckoutRequest.CheckoutItemRequest;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.DuplicateCheckResponse;
import com.shifa.oms.order.dto.LineItemRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.AppSettingsRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link OrderService} covering the financially and
 * behaviourally critical edge cases: the zero-total guard, the
 * {@code amountReceived > total} rejection (Req 7.10), the mandatory-screenshot
 * rule (Req 7.6), correct payment classification/status on creation (Req 7.7-7.9,
 * 8.2), storefront checkout pricing/COD (Req 3.6), and duplicate detection
 * (Req 22.2). Repositories and storage are mocked so these run without a DB.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private CourierRecordRepository courierRecordRepository;
    @Mock
    private CourierCompanyRepository courierCompanyRepository;
    @Mock
    private CouponRepository couponRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private AppSettingsRepository appSettingsRepository;
    @Mock
    private com.shifa.oms.inventory.StockMovementRepository stockMovementRepository;

    private OrderService service;
    /** Captures every outbox row written during a test, for confirmation assertions. */
    private List<OutboxEvent> savedOutboxEvents;

    private final AuthPrincipal salesperson = new AuthPrincipal(5L, "sales1", Role.SALESPERSON);
    private final AuthPrincipal admin = new AuthPrincipal(1L, "admin", Role.ADMIN);

    @BeforeEach
    void setUp() {
        TrackingService trackingService = new TrackingService(
                orderRepository, courierRecordRepository, courierCompanyRepository);
        // CouponService is a concrete class (not mockable on this JVM); use a real
        // instance over mocked repositories so the coupon path is genuinely exercised.
        CouponService couponService = new CouponService(
                couponRepository, orderRepository, new CheckoutPricing(productRepository));
        lenient().when(couponRepository.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));
        // Order-confirmation notifier: real collaborators over mocked interface
        // repositories (concrete classes are not mockable on this JVM). Captures the
        // WHATSAPP_NOTIFY outbox rows the notifier writes so tests can assert on them.
        savedOutboxEvents = new ArrayList<>();
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> {
            OutboxEvent event = inv.getArgument(0);
            savedOutboxEvents.add(event);
            return event;
        });
        AppSettings settings = new AppSettings();
        settings.setLegalName("Shifa Herbal Pvt Ltd");
        lenient().when(appSettingsRepository.findById(AppSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings));
        com.shifa.oms.settings.SettingsService settingsService =
                new com.shifa.oms.settings.SettingsService(appSettingsRepository);
        OrderConfirmationNotifier confirmationNotifier = new OrderConfirmationNotifier(
                new WhatsAppNotificationPublisher(
                        new WhatsAppMessageFactory(new WhatsAppTemplateRegistry()),
                        new OutboxEventPublisher(outboxEventRepository)),
                settingsService);
        // StockService: real instance over mocked interface repositories and the
        // real OutboxEventPublisher/SettingsService (concrete classes are not
        // mockable on this JVM). Products in these tests default to trackInventory
        // = false, so the sale path is a no-op unless a test opts in.
        com.shifa.oms.inventory.StockService stockService = new com.shifa.oms.inventory.StockService(
                productRepository,
                stockMovementRepository,
                new OutboxEventPublisher(outboxEventRepository),
                settingsService);
        service = new OrderService(
                orderRepository,
                productRepository,
                new OrderCodeGenerator(),
                storageService,
                new SalespersonScopeResolver(),
                trackingService,
                couponService,
                confirmationNotifier,
                stockService);
        // Order code generation asks the repo whether a candidate is taken.
        lenient().when(orderRepository.existsByOrderCode(anyString())).thenReturn(false);
        lenient().when(orderRepository.save(any(OrderEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private Product product(long id, String salePrice) {
        Product p = new Product("SKU-" + id, "Product " + id, "d",
                new BigDecimal("999.00"), new BigDecimal(salePrice), ProductVisibility.PUBLISHED);
        return p;
    }

    private CreateOrderRequest orderRequest(List<LineItemRequest> items,
                                            BigDecimal amountReceived, String screenshotKey) {
        return new CreateOrderRequest("Asha", "9812345678", "12 MG Road",
                "Pune", "Maharashtra", "411001", items, amountReceived, screenshotKey);
    }

    // --- Zero-total guard ---------------------------------------------------

    @Test
    void salespersonOrderRejectsZeroTotal() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "0.00")));
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 2, null)), BigDecimal.ZERO, null);

        assertThatThrownBy(() -> service.createSalespersonOrder(request, salesperson))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("positive Total_Amount");
    }

    @Test
    void storefrontOrderRejectsZeroTotal() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "0.00")));
        CheckoutRequest request = new CheckoutRequest("Asha", "9812345678", "12 MG Road",
                "Pune", "Maharashtra", "411001", List.of(new CheckoutItemRequest(1L, 1)));

        assertThatThrownBy(() -> service.createStorefrontOrder(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("positive Total_Amount");
    }

    // --- amount_received > total (Req 7.10) --------------------------------

    @Test
    void salespersonOrderRejectsAmountReceivedExceedingTotal() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "100.00")));
        // total = 100, received = 150 → rejected. Screenshot present so 7.6 passes first.
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 1, null)), new BigDecimal("150.00"), "payments/x.jpg");

        assertThatThrownBy(() -> service.createSalespersonOrder(request, salesperson))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds Total_Amount");
    }

    // --- Mandatory screenshot when money received (Req 7.6) -----------------

    @Test
    void salespersonOrderRequiresScreenshotWhenAmountReceived() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "100.00")));
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 1, null)), new BigDecimal("50.00"), null);

        assertThatThrownBy(() -> service.createSalespersonOrder(request, salesperson))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Payment_Screenshot");
    }

    // --- Successful creation & classification ------------------------------

    @Test
    void salespersonCodOrderStartsPendingApprovalAsCod() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "120.00")));
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 2, null)), BigDecimal.ZERO, null);

        OrderResponse response = service.createSalespersonOrder(request, salesperson);

        assertThat(response.orderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.COD);
        assertThat(response.totalAmount()).isEqualByComparingTo("240.00");
        assertThat(response.codAmount()).isEqualByComparingTo("240.00");
        assertThat(response.source()).isEqualTo(OrderSource.SALESPERSON);
        assertThat(response.items()).hasSize(1);
        assertThat(response.orderCode()).startsWith("SHR-");
    }

    @Test
    void salespersonPartiallyPaidOrderUsesEditedRateAndScreenshot() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "100.00")));
        // Override rate to 200; qty 1 → total 200, received 50 → partially paid, cod 150.
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 1, new BigDecimal("200.00"))),
                new BigDecimal("50.00"), "payments/proof.jpg");

        OrderResponse response = service.createSalespersonOrder(request, salesperson);

        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(response.totalAmount()).isEqualByComparingTo("200.00");
        assertThat(response.remainingAmount()).isEqualByComparingTo("150.00");
        assertThat(response.codAmount()).isEqualByComparingTo("150.00");
        assertThat(response.paymentScreenshotAvailable()).isTrue();
    }

    @Test
    void storefrontOrderIsCodPendingApprovalWithNoCreator() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "150.00")));
        CheckoutRequest request = new CheckoutRequest("Ravi", "9800011122", "5 Park St",
                "Kolkata", "West Bengal", "700016", List.of(new CheckoutItemRequest(1L, 2)));

        OrderEntity order = service.createStorefrontOrder(request);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.COD);
        assertThat(order.getSource()).isEqualTo(OrderSource.STOREFRONT);
        assertThat(order.getCreatedBy()).isNull();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("300.00");
        assertThat(order.getCodAmount()).isEqualByComparingTo("300.00");
        assertThat(order.getAmountReceived()).isEqualByComparingTo("0.00");
        assertThat(order.getStatusHistory()).hasSize(1);
        assertThat(order.getStatusHistory().get(0).getToStatus())
                .isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        assertThat(order.getStatusHistory().get(0).getFromStatus()).isNull();
    }

    // --- Order-confirmation notification on checkout (ROADMAP 1.2) ----------

    @Test
    void storefrontOrderEnqueuesExactlyOneOrderConfirmationEvent() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "150.00")));
        CheckoutRequest request = new CheckoutRequest("Ravi", "9800011122", "5 Park St",
                "Kolkata", "West Bengal", "700016", List.of(new CheckoutItemRequest(1L, 2)));

        service.createStorefrontOrder(request);

        // Exactly one WHATSAPP_NOTIFY outbox row is enqueued, and it is the
        // customer-facing ORDER_CONFIRMED confirmation addressed to the buyer's
        // mobile — flowing through the existing outbox (never sent inline).
        List<OutboxEvent> confirmations = savedOutboxEvents.stream()
                .filter(e -> OutboxEvent.EVENT_WHATSAPP_NOTIFY.equals(e.getEventType()))
                .filter(e -> "ORDER_CONFIRMED".equals(String.valueOf(e.getPayload().get("event"))))
                .toList();
        assertThat(confirmations).hasSize(1);
        OutboxEvent confirmation = confirmations.get(0);
        assertThat(confirmation.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(confirmation.getPayload().get("templateName")).isEqualTo("order_confirmed");
        assertThat(confirmation.getPayload().get("recipientMobile")).isEqualTo("9800011122");
    }

    // --- Coupon applied at storefront checkout (Phase D) --------------------

    @Test
    void storefrontOrderAppliesCouponAndReducesTotalAndCod() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "150.00")));
        // Subtotal = 2 x 150 = 300; a flat 50-off coupon reduces the net payable to 250.
        Coupon coupon = new Coupon("SAVE50", "flat 50", CouponType.FLAT, new java.math.BigDecimal("50.00"),
                null, null, true, null, null, null, null);
        when(couponRepository.findByCode("SAVE50")).thenReturn(Optional.of(coupon));
        when(orderRepository.countByCouponCodeAndCustomerMobile("SAVE50", "9800011122")).thenReturn(0L);

        CheckoutRequest request = new CheckoutRequest("Ravi", "9800011122", "5 Park St",
                "Kolkata", "West Bengal", "700016",
                List.of(new CheckoutItemRequest(1L, 2)), "SAVE50");

        OrderEntity order = service.createStorefrontOrder(request);

        assertThat(order.getCouponCode()).isEqualTo("SAVE50");
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("50.00");
        // Net total (300 - 50) drives COD.
        assertThat(order.getTotalAmount()).isEqualByComparingTo("250.00");
        assertThat(order.getCodAmount()).isEqualByComparingTo("250.00");
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.COD);
    }

    // --- Auto-decrement of tracked stock on order creation (Feature 1) ------

    @Test
    void storefrontOrderDecrementsTrackedStockAndRecordsSaleMovement() {
        Product tracked = product(1L, "150.00");
        tracked.setTrackInventory(true);
        tracked.setStockQuantity(10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(tracked));
        CheckoutRequest request = new CheckoutRequest("Ravi", "9800011122", "5 Park St",
                "Kolkata", "West Bengal", "700016", List.of(new CheckoutItemRequest(1L, 2)));

        service.createStorefrontOrder(request);

        assertThat(tracked.getStockQuantity()).isEqualTo(8);
        verify(stockMovementRepository).save(any(com.shifa.oms.inventory.StockMovement.class));
    }

    @Test
    void storefrontOrderRejectsInsufficientTrackedStock() {
        Product tracked = product(1L, "150.00");
        tracked.setTrackInventory(true);
        tracked.setStockQuantity(1);
        when(productRepository.findById(1L)).thenReturn(Optional.of(tracked));
        CheckoutRequest request = new CheckoutRequest("Ravi", "9800011122", "5 Park St",
                "Kolkata", "West Bengal", "700016", List.of(new CheckoutItemRequest(1L, 3)));

        assertThatThrownBy(() -> service.createStorefrontOrder(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Insufficient stock");
        assertThat(tracked.getStockQuantity()).isEqualTo(1);
    }

    @Test
    void storefrontOrderDoesNotDecrementUntrackedProduct() {
        Product untracked = product(1L, "150.00");
        // trackInventory defaults to false.
        untracked.setStockQuantity(3);
        when(productRepository.findById(1L)).thenReturn(Optional.of(untracked));
        CheckoutRequest request = new CheckoutRequest("Ravi", "9800011122", "5 Park St",
                "Kolkata", "West Bengal", "700016", List.of(new CheckoutItemRequest(1L, 2)));

        service.createStorefrontOrder(request);

        assertThat(untracked.getStockQuantity()).isEqualTo(3);
    }

    // --- Per-line HSN + GST rate snapshot on creation (Feature 2) -----------

    @Test
    void salespersonOrderLineSnapshotsProductHsnAndGstRate() {
        Product product = product(1L, "120.00");
        product.setHsnCode("3004");
        product.setGstRate(new BigDecimal("12.00"));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 2, null)), BigDecimal.ZERO, null);

        OrderResponse response = service.createSalespersonOrder(request, salesperson);

        assertThat(response.items()).hasSize(1);
        OrderResponse.LineItemResponse line = response.items().get(0);
        assertThat(line.hsnCode()).isEqualTo("3004");
        assertThat(line.gstRate()).isEqualByComparingTo("12.00");
    }

    @Test
    void storefrontOrderLineSnapshotsProductHsnAndGstRate() {
        Product product = product(1L, "150.00");
        product.setHsnCode("3003");
        product.setGstRate(new BigDecimal("5.00"));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        CheckoutRequest request = new CheckoutRequest("Ravi", "9800011122", "5 Park St",
                "Kolkata", "West Bengal", "700016", List.of(new CheckoutItemRequest(1L, 1)));

        OrderEntity order = service.createStorefrontOrder(request);

        assertThat(order.getLineItems()).hasSize(1);
        assertThat(order.getLineItems().get(0).getHsnCode()).isEqualTo("3003");
        assertThat(order.getLineItems().get(0).getGstRate()).isEqualByComparingTo("5.00");
    }

    @Test
    void productNotFoundIsRejected() {
        when(productRepository.findById(9L)).thenReturn(Optional.empty());
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(9L, 1, null)), BigDecimal.ZERO, null);

        assertThatThrownBy(() -> service.createSalespersonOrder(request, salesperson))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not exist");
    }

    // --- Duplicate detection (Req 22.2) ------------------------------------

    @Test
    void duplicateCheckReportsPriorOrders() {
        when(orderRepository.countByCustomerMobile("9812345678")).thenReturn(3L);

        DuplicateCheckResponse response = service.duplicateCheck("9812345678");

        assertThat(response.hasPriorOrders()).isTrue();
        assertThat(response.priorOrderCount()).isEqualTo(3L);
        assertThat(response.mobile()).isEqualTo("9812345678");
    }

    @Test
    void duplicateCheckReportsNoPriorOrders() {
        when(orderRepository.countByCustomerMobile("9000000000")).thenReturn(0L);

        DuplicateCheckResponse response = service.duplicateCheck("9000000000");

        assertThat(response.hasPriorOrders()).isFalse();
        assertThat(response.priorOrderCount()).isZero();
    }

    // --- Order-detail shipment fields (AWB / courier tracking) --------------

    /** Builds a minimal persisted-style order with the given id for detail lookups. */
    private OrderEntity persistedOrder(long id) {
        OrderEntity order = new OrderEntity("SHR-000123", OrderSource.SALESPERSON, 5L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(new BigDecimal("240.00"), BigDecimal.ZERO, new BigDecimal("240.00"),
                new BigDecimal("240.00"), PaymentStatus.COD);
        order.setOrderStatus(OrderStatus.COURIER_ASSIGNED);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    @Test
    void orderDetailWithCourierRecordExposesShipmentFields() {
        OrderEntity order = persistedOrder(7L);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));

        CourierRecord record = new CourierRecord(7L);
        record.assign(2L, "AWB123456789", "labels/shipping/x.pdf", LocalDate.of(2025, 6, 15));
        when(courierRecordRepository.findByOrderId(7L)).thenReturn(Optional.of(record));

        CourierCompany company = new CourierCompany("BlueDart", "https://track.example.com/{awb}");
        when(courierCompanyRepository.findById(2L)).thenReturn(Optional.of(company));

        OrderResponse response = service.getOrder(7L, admin);

        assertThat(response.awb()).isEqualTo("AWB123456789");
        assertThat(response.courierName()).isEqualTo("BlueDart");
        assertThat(response.trackingUrl()).isEqualTo("https://track.example.com/AWB123456789");
        assertThat(response.estimatedDelivery()).isEqualTo(LocalDate.of(2025, 6, 15));
    }

    @Test
    void orderDetailWithoutCourierRecordHasNullShipmentFields() {
        OrderEntity order = persistedOrder(8L);
        when(orderRepository.findById(8L)).thenReturn(Optional.of(order));
        when(courierRecordRepository.findByOrderId(8L)).thenReturn(Optional.empty());

        OrderResponse response = service.getOrder(8L, admin);

        assertThat(response.awb()).isNull();
        assertThat(response.courierName()).isNull();
        assertThat(response.trackingUrl()).isNull();
        assertThat(response.estimatedDelivery()).isNull();
    }
}
