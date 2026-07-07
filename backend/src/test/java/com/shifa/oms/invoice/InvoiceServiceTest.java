package com.shifa.oms.invoice;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.AppSettingsRepository;
import com.shifa.oms.settings.SettingsService;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvoiceService} using a mocked {@link OrderRepository}
 * (an interface) and a real {@link SalespersonScopeResolver}, so no disk or
 * database is touched:
 * <ul>
 *   <li>the authenticated admin path returns well-formed PDF bytes (starting
 *       with {@code %PDF-}) and a filename derived from the order code;</li>
 *   <li>salesperson scoping is honoured — a salesperson resolves via
 *       {@code findByIdAndCreatedBy} and an out-of-scope id yields 404;</li>
 *   <li>the public by-order-code path returns well-formed PDF bytes for a COD
 *       order;</li>
 *   <li>an unknown order code yields a 404 ({@link ResourceNotFoundException}).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    private OrderRepository orderRepository;

    // SettingsService is a concrete class; mock its (interface) repository instead
    // and use a real SettingsService so behaviour matches production.
    @Mock
    private AppSettingsRepository appSettingsRepository;

    @Mock
    private ProductRepository productRepository;

    private InvoiceService invoiceService;

    private static final AuthPrincipal ADMIN = new AuthPrincipal(1L, "admin", Role.ADMIN);
    private static final AuthPrincipal SALES = new AuthPrincipal(7L, "sales", Role.SALESPERSON);

    @BeforeEach
    void setUp() {
        SettingsService settingsService = new SettingsService(appSettingsRepository);
        invoiceService = new InvoiceService(orderRepository, new SalespersonScopeResolver(),
                settingsService, productRepository);
        // Default: GST disabled, so invoices render as the plain invoice.
        lenient().when(appSettingsRepository.findById(AppSettings.SINGLETON_ID))
                .thenReturn(Optional.of(AppSettings.defaults()));
    }

    @Test
    void adminInvoiceReturnsWellFormedPdfWithOrderCodeFilename() {
        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        InvoiceService.InvoiceDocument doc = invoiceService.invoicePdf(5L, ADMIN);

        assertThat(doc.content()).isNotEmpty();
        assertThat(new String(doc.content(), 0, 5)).startsWith("%PDF-");
        assertThat(doc.filename()).isEqualTo("invoice-SHR-000123.pdf");
    }

    @Test
    void salespersonInvoiceIsScopedToOwnOrders() {
        OrderEntity order = order(PaymentStatus.COD,
                new BigDecimal("240.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("240.00"), new BigDecimal("240.00"));
        when(orderRepository.findByIdAndCreatedBy(5L, 7L)).thenReturn(Optional.of(order));

        InvoiceService.InvoiceDocument doc = invoiceService.invoicePdf(5L, SALES);

        assertThat(new String(doc.content(), 0, 5)).startsWith("%PDF-");
    }

    @Test
    void salespersonOutOfScopeOrderYields404() {
        when(orderRepository.findByIdAndCreatedBy(9L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.invoicePdf(9L, SALES))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void publicInvoiceByOrderCodeReturnsWellFormedPdfForCodOrder() {
        OrderEntity order = order(PaymentStatus.COD,
                new BigDecimal("240.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("240.00"), new BigDecimal("240.00"));
        when(orderRepository.findByOrderCode("SHR-000123")).thenReturn(Optional.of(order));

        InvoiceService.InvoiceDocument doc = invoiceService.invoicePdfByOrderCode("SHR-000123");

        assertThat(doc.content()).isNotEmpty();
        assertThat(new String(doc.content(), 0, 5)).startsWith("%PDF-");
        assertThat(doc.filename()).isEqualTo("invoice-SHR-000123.pdf");
    }

    @Test
    void unknownOrderCodeYields404() {
        when(orderRepository.findByOrderCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> invoiceService.invoicePdfByOrderCode("NOPE"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void gstEnabledInvoiceRendersWellFormedTaxInvoicePdf() {
        AppSettings gst = new AppSettings();
        gst.setGstEnabled(true);
        gst.setGstin("23ABCDE1234F1Z5");
        gst.setLegalName("Shifa Herbal Remedies");
        gst.setState("Maharashtra");
        gst.setStateCode("27");
        gst.setGstRatePercent(new BigDecimal("5.00"));
        gst.setPricesIncludeGst(true);
        when(appSettingsRepository.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.of(gst));

        com.shifa.oms.product.Product product = new com.shifa.oms.product.Product(
                "SKU-1", "Neem Capsules", "d", new BigDecimal("120.00"), new BigDecimal("120.00"),
                com.shifa.oms.product.ProductVisibility.PUBLISHED);
        product.setHsnCode("3004");
        when(productRepository.findAllById(java.util.Set.of(1L)))
                .thenReturn(java.util.List.of(product));

        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        InvoiceService.InvoiceDocument doc = invoiceService.invoicePdf(5L, ADMIN);

        assertThat(doc.content()).isNotEmpty();
        assertThat(new String(doc.content(), 0, 5)).startsWith("%PDF-");
    }

    // --- Helper -------------------------------------------------------------

    private OrderEntity order(PaymentStatus paymentStatus, BigDecimal total,
                              BigDecimal received, BigDecimal remaining, BigDecimal cod) {
        OrderEntity order = new OrderEntity(
                "SHR-000123", OrderSource.SALESPERSON, 7L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.addLineItem(new OrderLineItem(1L, "Neem Capsules", 2,
                new BigDecimal("120.00"), new BigDecimal("240.00")));
        order.applyAmounts(total, received, remaining, cod, paymentStatus);
        order.setOrderStatus(OrderStatus.APPROVED);
        return order;
    }
}
