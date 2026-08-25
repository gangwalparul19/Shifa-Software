package com.shifa.oms.gst;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.gst.domain.ClassifiedOrder;
import com.shifa.oms.gst.domain.CreditNote;
import com.shifa.oms.gst.domain.CreditNoteProjection;
import com.shifa.oms.gst.domain.CreditNoteProjection.OrderReturnView;
import com.shifa.oms.gst.domain.DocRow;
import com.shifa.oms.gst.domain.DocumentCategory;
import com.shifa.oms.gst.domain.GstDocumentClassifier;
import com.shifa.oms.gst.domain.GstEngine;
import com.shifa.oms.gst.domain.GstEngine.GstLine;
import com.shifa.oms.gst.domain.GstEngine.GstOrder;
import com.shifa.oms.gst.domain.Gstr1Builder;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.domain.HsnCompliance;
import com.shifa.oms.gst.domain.StateCodeMaster;
import com.shifa.oms.gst.domain.SupplyType;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.returns.OrderReturn;
import com.shifa.oms.returns.OrderReturnRepository;
import com.shifa.oms.returns.ReturnStatus;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.SettingsService;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only application service that assembles a portal-ready {@link Gstr1Return}
 * for a reporting period (GST filing compliance, Tier 1 — composes Reqs 1–6).
 *
 * <p>Reuse-first, mirroring the shipped {@link GstAccountingService}: it loads the
 * period's revenue orders (excluding CANCELLED/REJECTED), maps each to a pure
 * {@link ClassifiedOrder} using the immutable line snapshots + the seller state,
 * projects refunded returns into {@link CreditNote}s, derives the Table-13
 * documents-issued rows from the invoice-number series, resolves the Table-12 HSN
 * UQC map from the catalogue, and delegates all section assembly to the pure
 * {@link Gstr1Builder}. All tax math stays in the pure {@link GstEngine} (Req 14.1),
 * and nothing rewrites the stored line tax snapshots (Req 1.8, 14.3).
 */
@Service
public class Gstr1ReturnService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    /** Orders excluded from outward tax figures (Req 2.6 for returns, revenue window). */
    private static final Set<OrderStatus> NON_REVENUE =
            EnumSet.of(OrderStatus.CANCELLED, OrderStatus.REJECTED);

    private static final String DOC_NATURE = "Invoices for outward supply";

    /** In-code statutory reference (no Spring bean needed). */
    private final StateCodeMaster stateCodes = new StateCodeMaster();

    private final OrderRepository orderRepository;
    private final OrderReturnRepository orderReturnRepository;
    private final ProductRepository productRepository;
    private final SettingsService settingsService;
    private final Clock clock;

    @Autowired
    public Gstr1ReturnService(OrderRepository orderRepository,
                              OrderReturnRepository orderReturnRepository,
                              ProductRepository productRepository,
                              SettingsService settingsService) {
        this(orderRepository, orderReturnRepository, productRepository, settingsService,
                Clock.system(ZONE));
    }

    public Gstr1ReturnService(OrderRepository orderRepository,
                              OrderReturnRepository orderReturnRepository,
                              ProductRepository productRepository,
                              SettingsService settingsService,
                              Clock clock) {
        this.orderRepository = orderRepository;
        this.orderReturnRepository = orderReturnRepository;
        this.productRepository = productRepository;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /** Resolves a nullable from/to to a concrete window, defaulting to the current month. */
    public Period resolvePeriod(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate f = from != null ? from : today.withDayOfMonth(1);
        LocalDate t = to != null ? to : today;
        if (f.isAfter(t)) {
            throw new ValidationException("The 'from' date must not be after the 'to' date.");
        }
        return new Period(f, t);
    }

    /**
     * Build the GSTR-1 return for the reporting period (Reqs 1–6). Read-only; every
     * figure derives from immutable order line snapshots + the seller GST settings.
     */
    @Transactional(readOnly = true)
    public Gstr1Return build(LocalDate from, LocalDate to) {
        Period p = resolvePeriod(from, to);
        AppSettings seller = settingsService.getSettings();
        String sellerState = seller.getState();
        String sellerGstin = seller.getGstin();

        // Revenue orders (excludes CANCELLED/REJECTED) → classified outward orders.
        List<OrderEntity> revenue = revenueOrders(p);
        List<ClassifiedOrder> classified = new ArrayList<>(revenue.size());
        for (OrderEntity o : revenue) {
            classified.add(classify(o, sellerState));
        }

        // Credit/debit notes from REFUNDED returns whose refund date falls in the period,
        // joined to their original order; returns against cancelled/rejected originals are
        // dropped before projection (Req 2.6).
        List<CreditNote> notes = buildCreditNotes(p, sellerState);

        // Table-12 HSN → UQC map from the catalogue (default NOS resolved inside the builder).
        Map<String, String> uqcByHsn = buildUqcByHsn();

        // Table-13 documents issued: a SEPARATE, unfiltered period load so cancelled invoices
        // are still counted (Req 4.1, 4.2, 4.4).
        List<DocRow> docs = buildDocs(p);

        // HSN min length from the seller's aggregate turnover (Req 3.3).
        int hsnMinLength = HsnCompliance.minLength(seller.getAggregateTurnover());

        int month = p.from().getMonthValue();
        int year = p.from().getYear();

        return Gstr1Builder.build(classified, notes, uqcByHsn, docs,
                sellerGstin, sellerState, stateCodes, hsnMinLength, month, year);
    }

    // --- Classification -----------------------------------------------------

    private ClassifiedOrder classify(OrderEntity o, String sellerState) {
        GstOrder gstOrder = toGstOrder(o);
        SupplyType type = GstEngine.classify(o.getState(), sellerState);
        BigDecimal invoiceValue = invoiceValue(o);
        DocumentCategory category =
                GstDocumentClassifier.classify(o.getBuyerGstin(), type, invoiceValue);
        return new ClassifiedOrder(gstOrder, o.getOrderCode(), o.getBuyerGstin(),
                type, invoiceValue, category);
    }

    // --- Credit notes -------------------------------------------------------

    private List<CreditNote> buildCreditNotes(Period p, String sellerState) {
        List<CreditNote> notes = new ArrayList<>();
        for (OrderReturn r : orderReturnRepository.findAll()) {
            if (r.getStatus() != ReturnStatus.REFUNDED || !within(r.getCreatedAt(), p)) {
                continue;
            }
            OrderEntity original = orderRepository.findById(r.getOrderId()).orElse(null);
            if (original == null) {
                continue;
            }
            // Exclude returns against cancelled/rejected orders (Req 2.6).
            if (original.getOrderStatus() != null && NON_REVENUE.contains(original.getOrderStatus())) {
                continue;
            }
            GstOrder gstOrder = toGstOrder(original);
            SupplyType type = GstEngine.classify(original.getState(), sellerState);
            DocumentCategory category =
                    GstDocumentClassifier.classify(original.getBuyerGstin(), type, invoiceValue(original));
            String stateCode = stateCodes.resolve(original.getState()).orElse("");
            OrderReturnView view = new OrderReturnView(
                    r.getId(), original.getOrderCode(),
                    r.getCreatedAt() != null ? r.getCreatedAt().toLocalDate() : null,
                    r.getRefundAmount());
            notes.add(CreditNoteProjection.fromReturn(view, gstOrder, category, stateCode, sellerState));
        }
        return notes;
    }

    // --- UQC map ------------------------------------------------------------

    /**
     * HSN code → the product's UQC, keyed by the (trimmed) HSN so the builder can look
     * it up per Table-12 HSN row. Blank/unknown UQC is resolved to {@code NOS} inside
     * the builder ({@link com.shifa.oms.gst.domain.Uqc#resolve}). When several products
     * share an HSN, the last non-blank UQC wins.
     */
    private Map<String, String> buildUqcByHsn() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Product product : productRepository.findAll()) {
            String hsn = product.getHsnCode();
            if (hsn == null || hsn.isBlank()) {
                continue;
            }
            String uqc = product.getUqc();
            if (uqc != null && !uqc.isBlank()) {
                map.put(hsn.trim(), uqc.trim());
            } else {
                map.putIfAbsent(hsn.trim(), null);
            }
        }
        return map;
    }

    // --- Documents issued (Table 13) ----------------------------------------

    /**
     * Documents-issued summary from the period's orders' {@code invoice_number} values,
     * grouped by series prefix. The load is UNFILTERED (includes cancelled/rejected)
     * so cancelled invoices are still counted (Req 4.1, 4.2). {@code totalCount} is the
     * number of documents in the series; {@code cancelledCount} is those whose order is
     * CANCELLED/REJECTED. An empty period yields an empty list (Req 4.4).
     */
    private List<DocRow> buildDocs(Period p) {
        Map<String, DocAcc> byPrefix = new LinkedHashMap<>();
        for (OrderEntity o : allOrders(p)) {
            String invoiceNumber = o.getInvoiceNumber();
            if (invoiceNumber == null || invoiceNumber.isBlank()) {
                continue;
            }
            String number = invoiceNumber.trim();
            String prefix = seriesPrefix(number);
            long numeric = numericSuffix(number);
            boolean cancelled = o.getOrderStatus() != null && NON_REVENUE.contains(o.getOrderStatus());
            byPrefix.computeIfAbsent(prefix, k -> new DocAcc()).add(number, numeric, cancelled);
        }
        List<DocRow> docs = new ArrayList<>();
        for (DocAcc acc : byPrefix.values()) {
            docs.add(new DocRow(DOC_NATURE, acc.fromNumber, acc.toNumber,
                    acc.totalCount, acc.cancelledCount));
        }
        return docs;
    }

    /** The non-numeric leading portion of an invoice number (its series prefix). */
    private static String seriesPrefix(String invoiceNumber) {
        int i = invoiceNumber.length();
        while (i > 0 && Character.isDigit(invoiceNumber.charAt(i - 1))) {
            i--;
        }
        return invoiceNumber.substring(0, i);
    }

    /** The trailing numeric run of an invoice number as a long (0 when none). */
    private static long numericSuffix(String invoiceNumber) {
        int i = invoiceNumber.length();
        while (i > 0 && Character.isDigit(invoiceNumber.charAt(i - 1))) {
            i--;
        }
        String digits = invoiceNumber.substring(i);
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // --- Order → GstEngine view ---------------------------------------------

    private GstOrder toGstOrder(OrderEntity o) {
        List<GstLine> lines = new ArrayList<>();
        for (OrderLineItem li : o.getLineItems()) {
            lines.add(new GstLine(li.getHsnCode(), li.getProductName(), li.getGstRate(),
                    li.getQuantity(), li.getLineTotal()));
        }
        LocalDate date = o.getCreatedAt() != null ? o.getCreatedAt().toLocalDate() : LocalDate.now(clock);
        return new GstOrder(o.getId(), o.getState(), date, lines);
    }

    /** GST-inclusive invoice value = Σ line totals (Req 1.5 threshold + invoice-level rows). */
    private static BigDecimal invoiceValue(OrderEntity o) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderLineItem li : o.getLineItems()) {
            if (li.getLineTotal() != null) {
                total = total.add(li.getLineTotal());
            }
        }
        return total;
    }

    // --- Period loads -------------------------------------------------------

    /** Revenue orders in the window, excluding CANCELLED/REJECTED (matches GstAccountingService). */
    private List<OrderEntity> revenueOrders(Period p) {
        List<OrderEntity> out = new ArrayList<>();
        for (OrderEntity o : allOrders(p)) {
            if (o.getOrderStatus() == null || !NON_REVENUE.contains(o.getOrderStatus())) {
                out.add(o);
            }
        }
        return out;
    }

    /** All orders created in the window (unfiltered), for docs counting. */
    private List<OrderEntity> allOrders(Period p) {
        LocalDateTime fromTs = p.from().atStartOfDay();
        LocalDateTime toTs = p.to().plusDays(1).atStartOfDay();
        return orderRepository.findByCreatedAtBetween(fromTs, toTs);
    }

    private static boolean within(LocalDateTime ts, Period p) {
        if (ts == null) {
            return false;
        }
        LocalDate d = ts.toLocalDate();
        return !d.isBefore(p.from()) && !d.isAfter(p.to());
    }

    /** A resolved reporting window (inclusive dates). */
    public record Period(LocalDate from, LocalDate to) {
    }

    /** Mutable accumulator for a documents-issued series. */
    private static final class DocAcc {
        String fromNumber;
        String toNumber;
        long minNumeric = Long.MAX_VALUE;
        long maxNumeric = Long.MIN_VALUE;
        int totalCount;
        int cancelledCount;

        void add(String number, long numeric, boolean cancelled) {
            totalCount++;
            if (cancelled) {
                cancelledCount++;
            }
            if (numeric <= minNumeric) {
                minNumeric = numeric;
                fromNumber = number;
            }
            if (numeric >= maxNumeric) {
                maxNumeric = numeric;
                toNumber = number;
            }
        }
    }
}
