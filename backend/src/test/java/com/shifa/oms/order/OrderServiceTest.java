package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.CourierCompany;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.courier.TrackingService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.DuplicateCheckResponse;
import com.shifa.oms.order.dto.LineItemRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductImage;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link OrderService} covering the financially and
 * behaviourally critical edge cases of the salesperson order-entry path: the
 * zero-total guard, the {@code amountReceived > total} rejection (Req 7.10), the
 * mandatory-screenshot rule (Req 7.6), correct payment classification/status on
 * creation (Req 7.7-7.9, 8.2), and duplicate detection (Req 22.2). Repositories
 * and storage are mocked so these run without a DB.
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
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private AppSettingsRepository appSettingsRepository;
    @Mock
    private com.shifa.oms.inventory.StockMovementRepository stockMovementRepository;
    @Mock
    private com.shifa.oms.product.ProductImageRepository productImageRepository;

    private OrderService service;

    private final AuthPrincipal salesperson = new AuthPrincipal(5L, "sales1", Role.SALESPERSON);
    private final AuthPrincipal admin = new AuthPrincipal(1L, "admin", Role.ADMIN);

    @BeforeEach
    void setUp() {
        TrackingService trackingService = new TrackingService(
                orderRepository, courierRecordRepository, courierCompanyRepository);
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        AppSettings settings = new AppSettings();
        settings.setLegalName("Shifa Herbal Pvt Ltd");
        lenient().when(appSettingsRepository.findById(AppSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings));
        com.shifa.oms.settings.SettingsService settingsService =
                new com.shifa.oms.settings.SettingsService(appSettingsRepository);
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
                stockService,
                productImageRepository);
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
        // Lead source is now a required order-entry field (Req 4.1); these existing
        // payment/creation edge-case tests use WHATSAPP with no note/email.
        return new CreateOrderRequest("Asha", "9812345678", "12 MG Road",
                "Pune", "Maharashtra", "411001", items, amountReceived, screenshotKey,
                LeadSource.WHATSAPP, null, null, null, null);
    }

    // --- Order total round-off to nearest rupee (product-audit §4.6) --------

    @Test
    void salespersonOrderRoundsTotalUpToNearestRupee() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "2679.99")));
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 1, null)), BigDecimal.ZERO, null);

        OrderResponse response = service.createSalespersonOrder(request, salesperson);

        assertThat(response.totalAmount()).isEqualByComparingTo("2680.00");
        assertThat(response.codAmount()).isEqualByComparingTo("2680.00");
    }

    @Test
    void salespersonOrderRoundsTotalDownToNearestRupee() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "100.49")));
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 1, null)), BigDecimal.ZERO, null);

        OrderResponse response = service.createSalespersonOrder(request, salesperson);

        assertThat(response.totalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void salespersonFullPaymentOfRoundedDownTotalIsFullyPaidNotExceeding() {
        // Price 100.49 rounds down to 100.00; a customer who paid the pre-round
        // 100.49 must classify as FULLY_PAID (the 0.49 overage is absorbed), not rejected.
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "100.49")));
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 1, null)), new BigDecimal("100.49"), "payments/x.jpg");

        OrderResponse response = service.createSalespersonOrder(request, salesperson);

        assertThat(response.totalAmount()).isEqualByComparingTo("100.00");
        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.FULLY_PAID);
        assertThat(response.codAmount()).isEqualByComparingTo("0.00");
    }

    // --- Payment verification layer (product-audit §4.4) --------------------

    @Test
    void prepaidOrderStartsPaymentVerificationPending() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "100.00")));
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 1, null)), new BigDecimal("100.00"), "payments/x.jpg");

        OrderResponse response = service.createSalespersonOrder(request, salesperson);

        assertThat(response.paymentVerificationStatus())
                .isEqualTo(com.shifa.oms.order.PaymentVerificationStatus.PENDING);
    }

    @Test
    void codOrderHasNoPaymentVerification() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "120.00")));
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 2, null)), BigDecimal.ZERO, null);

        OrderResponse response = service.createSalespersonOrder(request, salesperson);

        assertThat(response.paymentVerificationStatus()).isNull();
    }

    // --- Alternate contact number (product-audit §4.5) ----------------------

    @Test
    void salespersonOrderPersistsAlternateMobileWhenProvided() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "120.00")));
        CreateOrderRequest request = new CreateOrderRequest("Asha", "9812345678", "12 MG Road",
                "Pune", "Maharashtra", "411001",
                List.of(new LineItemRequest(1L, 1, null)), BigDecimal.ZERO, null,
                LeadSource.WHATSAPP, null, null, null, "9800011122");

        OrderResponse response = service.createSalespersonOrder(request, salesperson);

        assertThat(response.alternateMobile()).isEqualTo("9800011122");
    }

    @Test
    void salespersonOrderLeavesAlternateMobileNullWhenOmitted() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "120.00")));
        CreateOrderRequest request = orderRequest(
                List.of(new LineItemRequest(1L, 1, null)), BigDecimal.ZERO, null);

        OrderResponse response = service.createSalespersonOrder(request, salesperson);

        assertThat(response.alternateMobile()).isNull();
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
        // Portal-punched orders carry the SHIFA_ADMIN channel (Req 1.3).
        assertThat(response.source()).isEqualTo(OrderSource.SHIFA_ADMIN);
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

    // --- Order-detail line-item image key (item 1) --------------------------

    @Test
    void orderDetailPopulatesLineItemImageKeyFromPrimaryPublishedImage() {
        OrderEntity order = persistedOrder(9L);
        order.addLineItem(new OrderLineItem(
                42L, "Ashwagandha", 2, new BigDecimal("120.00"), new BigDecimal("240.00")));
        when(orderRepository.findById(9L)).thenReturn(Optional.of(order));
        when(courierRecordRepository.findByOrderId(9L)).thenReturn(Optional.empty());
        // Repo returns published images ordered by (productId, sortOrder); the
        // first per product is its primary image.
        when(productImageRepository.findPublishedByProductIds(anyCollection()))
                .thenReturn(List.of(new ProductImage(42L, "products/ashwagandha.jpg", true, 0)));

        OrderResponse response = service.getOrder(9L, admin);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).imageKey()).isEqualTo("products/ashwagandha.jpg");
    }

    @Test
    void orderDetailLineItemImageKeyIsNullWhenProductHasNoPublishedImage() {
        OrderEntity order = persistedOrder(10L);
        order.addLineItem(new OrderLineItem(
                43L, "Neem", 1, new BigDecimal("50.00"), new BigDecimal("50.00")));
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(courierRecordRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(productImageRepository.findPublishedByProductIds(anyCollection()))
                .thenReturn(List.of());

        OrderResponse response = service.getOrder(10L, admin);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).imageKey()).isNull();
    }

    // --- Order-detail discount amount (item 2) ------------------------------

    @Test
    void orderDetailExposesDiscountAmountWhenApplied() {
        OrderEntity order = persistedOrder(11L);
        order.applyDiscount("SAVE40", new BigDecimal("40.00"));
        when(orderRepository.findById(11L)).thenReturn(Optional.of(order));
        when(courierRecordRepository.findByOrderId(11L)).thenReturn(Optional.empty());

        OrderResponse response = service.getOrder(11L, admin);

        assertThat(response.discountAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    void orderDetailDiscountAmountDefaultsToZero() {
        OrderEntity order = persistedOrder(12L);
        when(orderRepository.findById(12L)).thenReturn(Optional.of(order));
        when(courierRecordRepository.findByOrderId(12L)).thenReturn(Optional.empty());

        OrderResponse response = service.getOrder(12L, admin);

        assertThat(response.discountAmount()).isEqualByComparingTo("0.00");
    }
}
