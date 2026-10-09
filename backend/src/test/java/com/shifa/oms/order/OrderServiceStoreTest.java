package com.shifa.oms.order;

import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.courier.TrackingService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.LineItemRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.order.dto.StoreLineItemRequest;
import com.shifa.oms.order.dto.StoreOrderRequest;
import com.shifa.oms.order.dto.UpdateOrderRequest;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the in-shop (POS / counter) store-order path
 * {@link OrderService#createStoreOrder} (store-order feature). Covers: no payment
 * screenshot is required, ad-hoc (non-catalogue) line items are allowed, a
 * fully-paid sale is auto-approved + closed, a partial payment stays approved
 * with the balance tracked, the same-day-duplicate guard does not apply, and the
 * order is tagged {@code source = STORE} / in-house.
 *
 * <p>Built with a REAL {@link OrderWorkflowService} (concrete classes can't be
 * Mockito-mocked on this JVM) so the fast-forward actually walks the lifecycle.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceStoreTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private StorageService storageService;
    @Mock private CourierRecordRepository courierRecordRepository;
    @Mock private CourierCompanyRepository courierCompanyRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private com.shifa.oms.inventory.StockMovementRepository stockMovementRepository;
    @Mock private com.shifa.oms.product.ProductImageRepository productImageRepository;
    @Mock private AuditEventRepository auditEventRepository;

    private OrderService service;

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
        com.shifa.oms.inventory.StockService stockService = new com.shifa.oms.inventory.StockService(
                productRepository, stockMovementRepository,
                new OutboxEventPublisher(outboxEventRepository), settingsService);
        // Real workflow service (audit is best-effort against a mocked repo).
        OrderWorkflowService workflow = new OrderWorkflowService(
                new AuditService(auditEventRepository, new CurrentUserService()));
        service = new OrderService(
                orderRepository, productRepository, new OrderCodeGenerator(), storageService,
                new SalespersonScopeResolver(), trackingService, stockService, productImageRepository,
                new OutboxEventPublisher(outboxEventRepository), null, null, null,
                workflow, null, null, null);
        lenient().when(orderRepository.existsByOrderCode(anyString())).thenReturn(false);
        lenient().when(orderRepository.save(any(OrderEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private Product product(long id, String salePrice) {
        BigDecimal sp = new BigDecimal(salePrice);
        Product p = new Product("SKU-" + id, "Product " + id, "d",
                sp.max(new BigDecimal("999.00")), sp, ProductVisibility.PUBLISHED);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    /** A store request WITH a payment screenshot (now mandatory when money is received). */
    private StoreOrderRequest storeRequest(List<StoreLineItemRequest> items, BigDecimal amountReceived) {
        return storeRequest(items, amountReceived, "payments/counter-proof.jpg");
    }

    /** A store request with an explicit screenshot key (null/blank = no proof attached). */
    private StoreOrderRequest storeRequest(
            List<StoreLineItemRequest> items, BigDecimal amountReceived, String screenshotKey) {
        return new StoreOrderRequest(
                "Walk-in Asha", "9812345678", null, null, null, null,
                items, amountReceived, null, null, null, null, null, null,
                screenshotKey, null);
    }

    @Test
    void fullyPaidStoreOrderIsAutoApprovedAndClosedWithScreenshot() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "500.00")));
        // One product × 1 @ 500 = 500; paid in full at the counter WITH a screenshot.
        StoreOrderRequest request = storeRequest(
                List.of(new StoreLineItemRequest(1L, null, 1, null, null, null)),
                new BigDecimal("500.00"));

        OrderResponse res = service.createStoreOrder(request, admin);

        assertThat(res.source()).isEqualTo(OrderSource.STORE);
        assertThat(res.deliveryMethod()).isEqualTo(DeliveryMethod.IN_HOUSE);
        assertThat(res.paymentStatus()).isEqualTo(PaymentStatus.FULLY_PAID);
        assertThat(res.totalAmount()).isEqualByComparingTo("500.00");
        // Fully-paid walks all the way to a completed, closed sale.
        assertThat(res.orderStatus()).isEqualTo(OrderStatus.CLOSED);
        assertThat(res.customerOutstanding()).isEqualByComparingTo("0.00");
        // The counter payment proof is attached.
        assertThat(res.paymentScreenshotAvailable()).isTrue();
    }

    @Test
    void paidStoreOrderWithoutScreenshotIsRejected() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "500.00")));
        // Money received at the counter but NO screenshot attached → rejected.
        StoreOrderRequest request = storeRequest(
                List.of(new StoreLineItemRequest(1L, null, 1, null, null, null)),
                new BigDecimal("500.00"), null);

        assertThatThrownBy(() -> service.createStoreOrder(request, admin))
                .isInstanceOf(com.shifa.oms.common.ValidationException.class);
    }

    @Test
    void partialStoreOrderStaysApprovedWithTheBalanceTracked() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "1000.00")));
        // 1000 total, only 400 paid at the counter.
        StoreOrderRequest request = storeRequest(
                List.of(new StoreLineItemRequest(1L, null, 1, null, null, null)),
                new BigDecimal("400.00"));

        OrderResponse res = service.createStoreOrder(request, admin);

        assertThat(res.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        // Partial → stop at APPROVED, balance tracked as outstanding.
        assertThat(res.orderStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(res.customerOutstanding()).isEqualByComparingTo("600.00");
    }

    @Test
    void storeOrderAllowsAnAdHocConsultationFeeWithNoProduct() {
        // A single ad-hoc line (no productId): "Consultation fee" ₹300, GST 0.
        StoreOrderRequest request = storeRequest(
                List.of(new StoreLineItemRequest(
                        null, "Consultation fee", 1, new BigDecimal("300.00"), BigDecimal.ZERO, null)),
                new BigDecimal("300.00"));

        OrderResponse res = service.createStoreOrder(request, admin);

        assertThat(res.totalAmount()).isEqualByComparingTo("300.00");
        assertThat(res.orderStatus()).isEqualTo(OrderStatus.CLOSED);
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).productName()).isEqualTo("Consultation fee");
        assertThat(res.items().get(0).productId()).isNull();
    }

    @Test
    void storeOrderMixesCatalogueAndAdHocLines() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "200.00")));
        StoreOrderRequest request = storeRequest(
                List.of(
                        new StoreLineItemRequest(1L, null, 2, null, null, null),       // 2 × 200 = 400
                        new StoreLineItemRequest(null, "Consultation fee", 1,
                                new BigDecimal("150.00"), BigDecimal.ZERO, null)),       // 150
                new BigDecimal("550.00"));

        OrderResponse res = service.createStoreOrder(request, admin);

        assertThat(res.totalAmount()).isEqualByComparingTo("550.00");
        assertThat(res.items()).hasSize(2);
        assertThat(res.orderStatus()).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    void storeOrderDoesNotApplyTheSameDayDuplicateGuard() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "500.00")));
        // No stubbing of findActiveByCustomerMobileInWindow — the store path must
        // never call it. (Mockito would flag an unnecessary stub if we added one.)
        StoreOrderRequest request = storeRequest(
                List.of(new StoreLineItemRequest(1L, null, 1, null, null, null)),
                new BigDecimal("500.00"));

        OrderResponse res = service.createStoreOrder(request, admin);

        assertThat(res.orderStatus()).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    void adHocLineWithoutANameIsRejected() {
        StoreOrderRequest request = storeRequest(
                List.of(new StoreLineItemRequest(null, "  ", 1, new BigDecimal("100.00"), null, null)),
                new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.createStoreOrder(request, admin))
                .isInstanceOf(com.shifa.oms.common.ValidationException.class);
    }

    /**
     * Bug fix: a new payment screenshot attached on a PAYMENT_REJECTED resubmit was
     * being dropped (UpdateOrderRequest had no screenshot field). It must now be
     * stored, become the PRIMARY proof (so the re-review sees the fresh one), and
     * the order re-enters payment verification as PENDING.
     */
    @Test
    void resubmitWithNewScreenshotStoresItAsPrimaryAndRequeuesVerification() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "500.00")));
        OrderEntity order = new OrderEntity(
                "SHR-REJ1", OrderSource.SALESPERSON, 1L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.addLineItem(new OrderLineItem(1L, "Product 1", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00")));
        order.applyAmounts(new BigDecimal("500.00"), new BigDecimal("500.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, PaymentStatus.FULLY_PAID);
        // The original (now-disputed) proof.
        order.addPaymentScreenshot("payments/old-rejected.jpg", null, null, null, null);
        order.setOrderStatus(OrderStatus.PAYMENT_REJECTED);
        // Start from a REJECTED verification state to prove the resubmit re-queues it.
        order.recordPaymentVerification(PaymentVerificationStatus.REJECTED, 1L, null, "disputed");
        when(orderRepository.findById(50L)).thenReturn(Optional.of(order));

        UpdateOrderRequest req = new UpdateOrderRequest(
                "Asha", "9812345678", null, null, "12 MG Road", "Pune", "Maharashtra", "411001",
                List.of(new LineItemRequest(1L, 1, null)), LeadSource.WHATSAPP, null, null, null, null, null,
                new BigDecimal("500.00"), "payments/new-proof.jpg", null, null);

        OrderResponse res = service.resubmit(50L, req, admin);

        assertThat(res.orderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        // New proof stored AND promoted to primary (legacy column), old one kept.
        assertThat(order.getPaymentScreenshots()).extracting(s -> s.getStorageKey())
                .containsExactly("payments/old-rejected.jpg", "payments/new-proof.jpg");
        assertThat(order.getPaymentScreenshotKey()).isEqualTo("payments/new-proof.jpg");
        // Payment-rejected rework re-enters the verification queue.
        assertThat(order.getPaymentVerificationStatus()).isEqualTo(PaymentVerificationStatus.PENDING);
    }

    @Test
    void resubmitWithoutNewScreenshotKeepsExistingProofs() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L, "500.00")));
        OrderEntity order = new OrderEntity(
                "SHR-REJ2", OrderSource.SALESPERSON, 1L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.addLineItem(new OrderLineItem(1L, "Product 1", 1,
                new BigDecimal("500.00"), new BigDecimal("500.00")));
        order.applyAmounts(new BigDecimal("500.00"), new BigDecimal("500.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, PaymentStatus.FULLY_PAID);
        order.addPaymentScreenshot("payments/orig.jpg", null, null, null, null);
        order.setOrderStatus(OrderStatus.REJECTED); // admin reject (not payment)
        when(orderRepository.findById(51L)).thenReturn(Optional.of(order));

        UpdateOrderRequest req = new UpdateOrderRequest(
                "Asha Fixed", "9812345678", null, null, "12 MG Road", "Pune", "Maharashtra", "411001",
                List.of(new LineItemRequest(1L, 1, null)), LeadSource.WHATSAPP, null, null, null, null, null,
                null, null, null, null);

        OrderResponse res = service.resubmit(51L, req, admin);

        assertThat(res.orderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        // Plain field edit: the original proof is untouched, none added.
        assertThat(order.getPaymentScreenshots()).extracting(s -> s.getStorageKey())
                .containsExactly("payments/orig.jpg");
        assertThat(order.getPaymentScreenshotKey()).isEqualTo("payments/orig.jpg");
    }
}
