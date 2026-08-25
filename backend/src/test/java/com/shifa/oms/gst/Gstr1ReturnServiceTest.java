package com.shifa.oms.gst;

import com.shifa.oms.gst.domain.CdnrRow;
import com.shifa.oms.gst.domain.CdnurRow;
import com.shifa.oms.gst.domain.DocRow;
import com.shifa.oms.gst.domain.GstEngine;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.domain.HsnRow;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.returns.OrderReturn;
import com.shifa.oms.returns.OrderReturnRepository;
import com.shifa.oms.returns.ReturnStatus;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.AppSettingsRepository;
import com.shifa.oms.settings.SettingsService;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for {@link Gstr1ReturnService} (gst-filing-compliance task 7.2).
 *
 * <p>Exercises the read-only composition the service is responsible for: period windowing of the
 * repository load, exclusion of CANCELLED/REJECTED orders from the outward sections, credit notes
 * joined to their original orders (and returns against cancelled/rejected originals dropped),
 * Table-13 docs derived from the invoice-number series, the Table-12 UQC map resolved from the
 * product catalogue, and an empty period yielding an empty return with no error.
 *
 * <p>Per the project's Java 25 Mockito constraint (concrete classes are not mockable), only the
 * interface repositories are mocked; a <em>real</em> {@link SettingsService} is built over a mocked
 * {@link AppSettingsRepository}, and a fixed {@link Clock} pins "today" via the service's secondary
 * constructor. No Spring context / database is started.
 *
 * <p>Validates: Requirements 2.6, 4.3, 4.4, 5.5.
 */
class Gstr1ReturnServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    /** Fixed "today" so the default-period path (current month) is deterministic. */
    private static final LocalDate TODAY = LocalDate.of(2026, 2, 15);

    private static final String SELLER_STATE = "Madhya Pradesh";
    private static final String SELLER_GSTIN = "23AABCS1234F1Z5";
    /** A format-valid buyer GSTIN so an order classifies as B2B. */
    private static final String BUYER_GSTIN = "23AABCS1234F1Z5";

    private OrderRepository orderRepository;
    private OrderReturnRepository orderReturnRepository;
    private ProductRepository productRepository;
    private AppSettingsRepository appSettingsRepository;
    private SettingsService settingsService;
    private Gstr1ReturnService service;

    @BeforeEach
    void setUp() {
        orderRepository = Mockito.mock(OrderRepository.class);
        orderReturnRepository = Mockito.mock(OrderReturnRepository.class);
        productRepository = Mockito.mock(ProductRepository.class);
        appSettingsRepository = Mockito.mock(AppSettingsRepository.class);

        AppSettings seller = AppSettings.defaults();
        seller.setGstEnabled(true);
        seller.setGstin(SELLER_GSTIN);
        seller.setState(SELLER_STATE);
        seller.setStateCode("23");
        seller.setAggregateTurnover(null); // → 4-digit HSN minimum
        when(appSettingsRepository.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.of(seller));
        settingsService = new SettingsService(appSettingsRepository);

        // Sensible empty defaults; individual tests override as needed.
        when(orderRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(orderReturnRepository.findAll()).thenReturn(List.of());
        when(productRepository.findAll()).thenReturn(List.of());

        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
        service = new Gstr1ReturnService(orderRepository, orderReturnRepository, productRepository,
                settingsService, clock);
    }

    // --- Period windowing ----------------------------------------------------

    @Test
    void windowsThePeriodAsHalfOpenDayRangeOnTheRepository() {
        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);

        service.build(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 20));

        // The service loads orders once for revenue and once (unfiltered) for docs — same window.
        Mockito.verify(orderRepository, Mockito.atLeastOnce())
                .findByCreatedAtBetween(from.capture(), to.capture());
        assertThat(from.getAllValues()).allSatisfy(f ->
                assertThat(f).isEqualTo(LocalDate.of(2026, 1, 5).atStartOfDay()));
        assertThat(to.getAllValues()).allSatisfy(t ->
                assertThat(t).isEqualTo(LocalDate.of(2026, 1, 21).atStartOfDay())); // to + 1 day, exclusive
    }

    @Test
    void defaultsToTheCurrentMonthWhenDatesAreNull() {
        Gstr1Return ret = service.build(null, null);

        // Fixed today = 2026-02-15 → period Feb 2026.
        assertThat(ret.month()).isEqualTo(2);
        assertThat(ret.year()).isEqualTo(2026);
        assertThat(ret.sellerGstin()).isEqualTo(SELLER_GSTIN);
    }

    // --- Exclusion of CANCELLED / REJECTED (Req 2.6) -------------------------

    @Test
    void excludesCancelledAndRejectedOrdersFromOutwardSectionsAndReconciliation() {
        OrderEntity delivered = order("SHR-1", SELLER_STATE, OrderStatus.DELIVERED, null, "SHR/1",
                line("30049011", "5", 1, "105"));
        OrderEntity cancelled = order("SHR-2", SELLER_STATE, OrderStatus.CANCELLED, null, "SHR/2",
                line("30049011", "5", 1, "105"));
        OrderEntity rejected = order("SHR-3", SELLER_STATE, OrderStatus.REJECTED, null, "SHR/3",
                line("30049011", "5", 1, "105"));
        when(orderRepository.findByCreatedAtBetween(any(), any()))
                .thenReturn(List.of(delivered, cancelled, rejected));

        Gstr1Return ret = service.build(periodStart(), periodEnd());

        // Only the delivered order feeds B2CS; the two excluded orders contribute nothing.
        assertThat(ret.b2cs()).hasSize(1);
        assertThat(ret.b2cs().get(0).taxable()).isEqualByComparingTo("100.00");

        // Reconciliation reflects exactly one taxable line of 100.00 (105 incl. @5%).
        assertThat(ret.reconciliation().taxableOutward()).isEqualByComparingTo("100.00");
        assertThat(ret.reconciliation().outputTotal()).isEqualByComparingTo("5.00");
    }

    // --- Reconciliation to the GST engine (Req 5.5) --------------------------

    @Test
    void reconciliationMatchesGstEngineOverTheSameRevenueOrders() {
        OrderEntity intra = order("SHR-1", SELLER_STATE, OrderStatus.DELIVERED, null, "SHR/1",
                line("30049011", "5", 2, "210"));
        OrderEntity inter = order("SHR-2", "Maharashtra", OrderStatus.CLOSED, null, "SHR/2",
                line("30049011", "18", 1, "118"));
        // Excluded — must not affect the reconciliation.
        OrderEntity cancelled = order("SHR-3", SELLER_STATE, OrderStatus.CANCELLED, null, "SHR/3",
                line("30049011", "5", 5, "525"));
        when(orderRepository.findByCreatedAtBetween(any(), any()))
                .thenReturn(List.of(intra, inter, cancelled));

        Gstr1Return ret = service.build(periodStart(), periodEnd());

        // Independent expectation: run the pure engine over just the two revenue orders.
        GstEngine.Gstr3bSummary expected = GstEngine.compute(List.of(
                new GstEngine.GstOrder(1L, SELLER_STATE, TODAY, List.of(
                        new GstEngine.GstLine("30049011", "p", new BigDecimal("5"), 2, new BigDecimal("210")))),
                new GstEngine.GstOrder(2L, "Maharashtra", TODAY, List.of(
                        new GstEngine.GstLine("30049011", "p", new BigDecimal("18"), 1, new BigDecimal("118"))))
        ), SELLER_STATE).summary();

        assertThat(ret.reconciliation().taxableOutward()).isEqualByComparingTo(expected.taxableOutward());
        assertThat(ret.reconciliation().outputCgst()).isEqualByComparingTo(expected.outputCgst());
        assertThat(ret.reconciliation().outputSgst()).isEqualByComparingTo(expected.outputSgst());
        assertThat(ret.reconciliation().outputIgst()).isEqualByComparingTo(expected.outputIgst());
        assertThat(ret.reconciliation().outputTotal()).isEqualByComparingTo(expected.outputTotal());
        assertThat(ret.reconciliation().invoiceValue()).isEqualByComparingTo(expected.invoiceValue());
    }

    // --- Credit notes joined to originals (Req 2.6) --------------------------

    @Test
    void creditNotesJoinToOriginalsAndDropReturnsAgainstCancelledOrRejectedOriginals() {
        OrderEntity b2bOriginal = order("SHR-B2B", SELLER_STATE, OrderStatus.DELIVERED, BUYER_GSTIN,
                "SHR/1", line("30049011", "5", 2, "210"));
        OrderEntity b2csOriginal = order("SHR-B2C", SELLER_STATE, OrderStatus.DELIVERED, null,
                "SHR/2", line("30049011", "5", 1, "105"));
        OrderEntity cancelledOriginal = order("SHR-CAN", SELLER_STATE, OrderStatus.CANCELLED, null,
                "SHR/3", line("30049011", "5", 1, "105"));

        when(orderRepository.findById(101L)).thenReturn(Optional.of(b2bOriginal));
        when(orderRepository.findById(102L)).thenReturn(Optional.of(b2csOriginal));
        when(orderRepository.findById(103L)).thenReturn(Optional.of(cancelledOriginal));

        // R1 refunded in-period against B2B → CDNR; R2 refunded in-period against B2C → CDNUR;
        // R3 refunded against a cancelled original → dropped; R4 not refunded → dropped;
        // R5 refunded but its date is outside the period → dropped.
        OrderReturn r1 = refundedReturn(101L, "45.00", TODAY);
        OrderReturn r2 = refundedReturn(102L, "30.00", TODAY);
        OrderReturn r3 = refundedReturn(103L, "50.00", TODAY);
        OrderReturn r4 = returnWithStatus(101L, "20.00", TODAY, ReturnStatus.APPROVED);
        OrderReturn r5 = refundedReturn(102L, "15.00", TODAY.minusMonths(2));
        when(orderReturnRepository.findAll()).thenReturn(List.of(r1, r2, r3, r4, r5));

        Gstr1Return ret = service.build(periodStart(), periodEnd());

        assertThat(ret.cdnr()).hasSize(1);
        CdnrRow cdnr = ret.cdnr().get(0);
        assertThat(cdnr.originalOrderCode()).isEqualTo("SHR-B2B");
        assertThat(cdnr.placeOfSupply()).isEqualTo(SELLER_STATE);
        assertThat(cdnr.stateCode()).isEqualTo("23");

        assertThat(ret.cdnur()).hasSize(1);
        CdnurRow cdnur = ret.cdnur().get(0);
        assertThat(cdnur.originalOrderCode()).isEqualTo("SHR-B2C");
    }

    // --- Documents issued from the invoice-number series (Req 4.3, 4.4) ------

    @Test
    void derivesDocumentsIssuedSummaryFromInvoiceNumberSeries() {
        OrderEntity o1 = order("SHR-1", SELLER_STATE, OrderStatus.DELIVERED, null, "SHR/1",
                line("30049011", "5", 1, "105"));
        OrderEntity o2 = order("SHR-2", SELLER_STATE, OrderStatus.DELIVERED, null, "SHR/2",
                line("30049011", "5", 1, "105"));
        OrderEntity o3 = order("SHR-3", SELLER_STATE, OrderStatus.CANCELLED, null, "SHR/4",
                line("30049011", "5", 1, "105"));
        // No invoice number → not counted in the docs series.
        OrderEntity noInvoice = order("SHR-4", SELLER_STATE, OrderStatus.DELIVERED, null, null,
                line("30049011", "5", 1, "105"));
        when(orderRepository.findByCreatedAtBetween(any(), any()))
                .thenReturn(List.of(o1, o2, o3, noInvoice));

        Gstr1Return ret = service.build(periodStart(), periodEnd());

        assertThat(ret.docs()).hasSize(1);
        DocRow doc = ret.docs().get(0);
        assertThat(doc.fromNumber()).isEqualTo("SHR/1");
        assertThat(doc.toNumber()).isEqualTo("SHR/4");
        assertThat(doc.totalCount()).isEqualTo(3);       // SHR/1, SHR/2, SHR/4 (the un-numbered order is skipped)
        assertThat(doc.cancelledCount()).isEqualTo(1);   // SHR/4 is cancelled
    }

    // --- UQC map from the product catalogue (Req 3) --------------------------

    @Test
    void resolvesTable12UqcFromProductsWithNosDefaultForBlankUqc() {
        OrderEntity withUqc = order("SHR-1", SELLER_STATE, OrderStatus.DELIVERED, null, "SHR/1",
                line("30049011", "5", 1, "105"));
        OrderEntity blankUqc = order("SHR-2", SELLER_STATE, OrderStatus.DELIVERED, null, "SHR/2",
                line("33051010", "18", 1, "118"));
        when(orderRepository.findByCreatedAtBetween(any(), any()))
                .thenReturn(List.of(withUqc, blankUqc));
        when(productRepository.findAll()).thenReturn(List.of(
                product("30049011", "BTL"),
                product("33051010", null)));

        Gstr1Return ret = service.build(periodStart(), periodEnd());

        HsnRow withUqcRow = ret.hsn().stream()
                .filter(h -> h.hsn().equals("30049011")).findFirst().orElseThrow();
        assertThat(withUqcRow.uqc()).isEqualTo("BTL");

        HsnRow blankUqcRow = ret.hsn().stream()
                .filter(h -> h.hsn().equals("33051010")).findFirst().orElseThrow();
        assertThat(blankUqcRow.uqc()).isEqualTo("NOS"); // default resolved in the builder
    }

    // --- Empty period (Req 4.4) ----------------------------------------------

    @Test
    void emptyPeriodYieldsAnEmptyReturnWithoutError() {
        // All repositories already return empty from setUp.
        Gstr1Return ret = service.build(periodStart(), periodEnd());

        assertThat(ret).isNotNull();
        assertThat(ret.b2b()).isEmpty();
        assertThat(ret.b2cl()).isEmpty();
        assertThat(ret.b2cs()).isEmpty();
        assertThat(ret.cdnr()).isEmpty();
        assertThat(ret.cdnur()).isEmpty();
        assertThat(ret.hsn()).isEmpty();
        assertThat(ret.docs()).isEmpty();
        assertThat(ret.unresolvedStates()).isEmpty();
        assertThat(ret.reconciliation().taxableOutward()).isEqualByComparingTo("0.00");
        assertThat(ret.reconciliation().outputTotal()).isEqualByComparingTo("0.00");
    }

    // --- Fixtures ------------------------------------------------------------

    private static LocalDate periodStart() {
        return LocalDate.of(2026, 2, 1);
    }

    private static LocalDate periodEnd() {
        return LocalDate.of(2026, 2, 28);
    }

    private OrderEntity order(String orderCode, String state, OrderStatus status, String buyerGstin,
                              String invoiceNumber, OrderLineItem... lines) {
        OrderEntity o = new OrderEntity(orderCode, OrderSource.SALESPERSON, 1L,
                "Customer", "9990001111", "Addr", "City", state, "452001");
        o.setOrderStatus(status);
        o.setBuyerGstin(buyerGstin);
        o.setInvoiceNumber(invoiceNumber);
        for (OrderLineItem li : lines) {
            o.addLineItem(li);
        }
        // created_at is DB-managed (insertable=false) — set it directly for the period window.
        ReflectionTestUtils.setField(o, "createdAt", TODAY.atStartOfDay());
        return o;
    }

    private static OrderLineItem line(String hsn, String gstRate, int qty, String lineTotal) {
        BigDecimal rate = new BigDecimal(lineTotal).divide(new BigDecimal(qty));
        return new OrderLineItem(null, "Product " + hsn, hsn, new BigDecimal(gstRate),
                qty, rate, new BigDecimal(lineTotal));
    }

    private static Product product(String hsn, String uqc) {
        Product p = new Product("SKU-" + hsn, "Product " + hsn, "desc",
                new BigDecimal("999"), new BigDecimal("105"), ProductVisibility.PUBLISHED);
        p.setHsnCode(hsn);
        p.setUqc(uqc);
        return p;
    }

    private OrderReturn refundedReturn(Long orderId, String refund, LocalDate date) {
        return returnWithStatus(orderId, refund, date, ReturnStatus.REFUNDED);
    }

    private OrderReturn returnWithStatus(Long orderId, String refund, LocalDate date, ReturnStatus status) {
        OrderReturn r = new OrderReturn(orderId, "damaged", null, 1L);
        r.setRefundAmount(new BigDecimal(refund));
        r.changeStatus(status);
        ReflectionTestUtils.setField(r, "createdAt", date.atTime(10, 0));
        return r;
    }
}
