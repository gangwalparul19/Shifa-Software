package com.shifa.oms.label;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderStatusHistory;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.statemachine.OrderStatus;
import com.shifa.oms.statemachine.OrderStatusLifecycle;
import com.shifa.oms.statemachine.OrderStatusStateMachine;
import com.shifa.oms.statemachine.StatusHistoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Label Service (design "Label Service"; Req 10).
 *
 * <p><strong>Design — content vs rendering.</strong> Label generation is split
 * into a pure {@link LabelContentBuilder} that assembles the
 * {@link InternalLabelContent} model (order id, barcode value, customer details,
 * line items, and the COD-iff rule) and a {@link LabelPdfRenderer} that turns the
 * model into PDF bytes (with a Code128 barcode from {@link BarcodeGenerator}).
 * This separation lets the label content be property-tested without producing
 * bytes (Property 18/19).
 *
 * <p><strong>Single scannable barcode (label redesign feature).</strong> The
 * label prints exactly one scannable barcode: the delivery partner's barcode +
 * AWB once one has been allotted (so the courier scans straight into their own
 * system at pickup), or — only when no partner/AWB is allotted yet — our own
 * order-code barcode as a fallback. The RTO/packing scan flow ({@code
 * PackingService}'s barcode resolution) recognises both our order code and a
 * courier AWB, so scanning whichever barcode is actually printed still resolves
 * back to the order.
 *
 * <p><strong>Trigger — auto on approval.</strong>
 * {@link #generateInternalLabelOnApproval(OrderEntity, String)} is invoked from
 * {@code AdminOrderService} immediately after an order is approved, within the
 * same transaction: it renders the internal label, stores the PDF via
 * {@link StorageService} under {@code labels/internal/...}, and advances the
 * order {@code Approved → Label_Generated} through the shared state machine,
 * writing one {@code status_history} row (source {@code SYSTEM}) (Req 10.1-10.3,
 * 8.4).
 *
 * <p><strong>Print endpoints.</strong> {@link #internalLabelPdf(Long)} and
 * {@link #bulkInternalLabelPdf(List)} (re)render the label(s) deterministically
 * from the current order state so the admin can print one label or a single
 * combined PDF with one label block per requested order (Req 10.4). Regeneration
 * keeps the print path decoupled from where the archival copy is stored.
 */
@Service
public class LabelService {

    private static final Logger log = LoggerFactory.getLogger(LabelService.class);

    private static final String STORAGE_PREFIX = "labels/internal";
    private static final String SOURCE_SYSTEM = "SYSTEM";

    private final OrderRepository orderRepository;
    private final StorageService storageService;
    private final com.shifa.oms.settings.CompanyLogoService companyLogoService;
    private final com.shifa.oms.settings.SettingsService settingsService;
    /**
     * QuikShipX shipment mirror (nullable): supplies the courier name + AWB
     * (tracking number) to render as the label's courier barcode once QuikShipX
     * has allotted a tracking id, so the courier team scans the label at
     * pickup and it resolves straight to the AWB in their own system. Null in
     * the lightweight test constructors → the courier barcode section is simply
     * omitted (only the order barcode renders).
     */
    private final com.shifa.oms.quikshipx.OrderShipmentRepository orderShipmentRepository;
    /**
     * Generic/in-house courier record (nullable): the fallback courier + AWB
     * source for non-QuikShipX orders (Req 10.1). Null in the lightweight test
     * constructors.
     */
    private final com.shifa.oms.courier.CourierRecordRepository courierRecordRepository;
    private final com.shifa.oms.courier.CourierCompanyRepository courierCompanyRepository;
    private final LabelContentBuilder contentBuilder;
    private final LabelPdfRenderer pdfRenderer;
    private final OrderStatusStateMachine stateMachine;

    /** Test-friendly constructor without the company collaborators (no logo/seller on labels). */
    public LabelService(OrderRepository orderRepository, StorageService storageService) {
        this(orderRepository, storageService, null, null, null);
    }

    /** Constructor with the logo collaborator only (kept for callers that don't wire settings). */
    public LabelService(OrderRepository orderRepository, StorageService storageService,
                        com.shifa.oms.settings.CompanyLogoService companyLogoService) {
        this(orderRepository, storageService, companyLogoService, null, null);
    }

    /**
     * Constructor used by existing test call sites (5 args): wires the QuikShipX
     * shipment lookup only, leaving the generic courier-record lookup unwired
     * (in-house/legacy courier barcode falls back to omitted).
     */
    public LabelService(OrderRepository orderRepository, StorageService storageService,
                        com.shifa.oms.settings.CompanyLogoService companyLogoService,
                        com.shifa.oms.settings.SettingsService settingsService,
                        com.shifa.oms.quikshipx.OrderShipmentRepository orderShipmentRepository) {
        this(orderRepository, storageService, companyLogoService, settingsService,
                orderShipmentRepository, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LabelService(OrderRepository orderRepository, StorageService storageService,
                        com.shifa.oms.settings.CompanyLogoService companyLogoService,
                        com.shifa.oms.settings.SettingsService settingsService,
                        com.shifa.oms.quikshipx.OrderShipmentRepository orderShipmentRepository,
                        com.shifa.oms.courier.CourierRecordRepository courierRecordRepository,
                        com.shifa.oms.courier.CourierCompanyRepository courierCompanyRepository) {
        this.orderRepository = orderRepository;
        this.storageService = storageService;
        this.companyLogoService = companyLogoService;
        this.settingsService = settingsService;
        this.orderShipmentRepository = orderShipmentRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
        this.contentBuilder = new LabelContentBuilder();
        this.pdfRenderer = new LabelPdfRenderer(new BarcodeGenerator());
        this.stateMachine = new OrderStatusStateMachine();
    }

    /** The courier partner's display name + allotted AWB for an order's label, or both {@code null}. */
    private record CourierInfo(String name, String awb) {
        static final CourierInfo NONE = new CourierInfo(null, null);
    }

    /**
     * Resolves the courier partner name + AWB to render as the label's courier
     * barcode (label redesign feature): prefers the QuikShipX shipment mirror
     * (always displayed as {@value #QUIKSHIPX_DISPLAY_NAME} — the partner
     * actually selected — plus its {@code awb}, once a tracking id is allotted),
     * falling back to the generic {@code CourierRecord}/{@code CourierCompany}
     * pair for in-house/legacy orders. Returns {@link CourierInfo#NONE} when
     * neither source has an AWB yet (e.g. awaiting allotment, or an in-house
     * order with no courier at all) — the label then falls back to the order
     * barcode. The print/reprint endpoints re-read this fresh on every call, so
     * a label printed before allotment and reprinted after automatically picks
     * up the courier + AWB once they land (Req 10.4).
     */
    /**
     * Display name shown on the label for a QuikShipX-fulfilled shipment. The
     * courier partner the admin/salesperson actually selected is QuikShipX (the
     * aggregator); the sub-courier it allots under the hood (e.g. a mocked/real
     * "Direct_Delhivery") is an internal QuikShipX routing detail that means
     * nothing to our own packing/courier team, so the label always shows the
     * partner name "QuikShipX" instead of the raw sub-courier string.
     */
    private static final String QUIKSHIPX_DISPLAY_NAME = "QuikShipX";

    private CourierInfo courierInfoFor(OrderEntity order) {
        if (order.getId() == null) {
            return CourierInfo.NONE;
        }
        if (orderShipmentRepository != null) {
            java.util.Optional<com.shifa.oms.quikshipx.OrderShipment> shipment =
                    orderShipmentRepository.findByOrderId(order.getId());
            if (shipment.isPresent()) {
                String awb = shipment.get().getAwb();
                if (awb != null && !awb.isBlank()) {
                    return new CourierInfo(QUIKSHIPX_DISPLAY_NAME, awb);
                }
            }
        }
        if (courierRecordRepository != null) {
            java.util.Optional<com.shifa.oms.courier.CourierRecord> record =
                    courierRecordRepository.findByOrderId(order.getId());
            if (record.isPresent()) {
                String awb = record.get().getAwb();
                if (awb != null && !awb.isBlank()) {
                    String name = null;
                    if (courierCompanyRepository != null && record.get().getCourierCompanyId() != null) {
                        name = courierCompanyRepository.findById(record.get().getCourierCompanyId())
                                .map(com.shifa.oms.courier.CourierCompany::getName)
                                .orElse(null);
                    }
                    return new CourierInfo(name, awb);
                }
            }
        }
        return CourierInfo.NONE;
    }

    /** The configured company logo bytes for rendering, or {@code null} when none/absent. */
    private byte[] logoPng() {
        return companyLogoService != null ? companyLogoService.currentLogoPng().orElse(null) : null;
    }

    /** Seller/brand details for the label header + grid, sourced from app settings. */
    private LabelCompany company() {
        if (settingsService == null) {
            return LabelCompany.defaults();
        }
        try {
            com.shifa.oms.settings.AppSettings s = settingsService.getSettings();
            String brand = s.getLegalName() != null && !s.getLegalName().isBlank()
                    ? s.getLegalName() : "Shifa Herbal Remedies";
            String pickup = joinNonBlank(", ", s.getAddressLine(), s.getCity(), s.getState());
            // Seller GST No + header address so the label header mirrors the invoice
            // (brand + address + GST No under it). Shown only when GST is enabled.
            String gstin = s.isGstEnabled() ? blankToNull(s.getGstin()) : null;
            String stateWithCode = s.getState() != null ? s.getState() : "";
            if (s.getStateCode() != null && !s.getStateCode().isBlank()) {
                stateWithCode = (stateWithCode.isBlank() ? "" : stateWithCode + " ")
                        + "(" + s.getStateCode() + ")";
            }
            String headerAddress = joinNonBlank(", ", s.getAddressLine(), s.getCity(), stateWithCode);
            return new LabelCompany(brand, brand, pickup.isBlank() ? null : pickup,
                    gstin, headerAddress.isBlank() ? null : headerAddress);
        } catch (Exception e) {
            log.debug("Falling back to default label company (settings unavailable): {}", e.getMessage());
            return LabelCompany.defaults();
        }
    }

    private static String blankToNull(String v) {
        return (v != null && !v.isBlank()) ? v.trim() : null;
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(sep);
                }
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    /**
     * Generates the internal company label for a freshly approved order and moves
     * it to {@code Label_Generated} (Req 10.1, 10.2, 10.3).
     *
     * <p>Called within the approval transaction on an {@code Approved} order: it
     * builds the label content, renders the PDF, stores it via the storage
     * service, and applies the {@code Approved → Label_Generated} transition
     * (recording one status-history row). The caller persists the mutated order.
     *
     * @param order the approved order aggregate (mutated in place)
     * @param actor the acting principal (recorded as the transition actor)
     * @return the storage key of the archived label PDF
     */
    public String generateInternalLabelOnApproval(OrderEntity order, String actor) {
        CourierInfo courier = courierInfoFor(order);
        InternalLabelContent content = contentBuilder.buildInternal(
                order, company(), courier.name(), courier.awb());
        byte[] pdf = pdfRenderer.render(content, logoPng());

        StorageService.StoredObjectRef ref = storageService.store(
                STORAGE_PREFIX, order.getOrderCode() + ".pdf", "application/pdf", pdf);

        applyTransition(order, OrderStatus.LABEL_GENERATED, actor);
        log.debug("Generated internal label for order {} under key {}", order.getOrderCode(), ref.key());
        return ref.key();
    }

    /**
     * Renders the internal company label PDF for a single order (Req 10.4).
     *
     * @param orderId the order id
     * @return the single-label PDF bytes
     */
    @Transactional(readOnly = true)
    public byte[] internalLabelPdf(Long orderId) {
        OrderEntity order = requireOrder(orderId);
        CourierInfo courier = courierInfoFor(order);
        InternalLabelContent content = contentBuilder.buildInternal(
                order, company(), courier.name(), courier.awb());
        // Multi-pack (product-audit §4.2): print one label copy per box. Default
        // package count is 1 → a single label, unchanged from before.
        int copies = order.getPackageCount();
        if (copies <= 1) {
            return pdfRenderer.render(content, logoPng());
        }
        List<InternalLabelContent> blocks = new ArrayList<>(copies);
        for (int i = 0; i < copies; i++) {
            blocks.add(content);
        }
        return pdfRenderer.render(blocks, logoPng());
    }

    /**
     * Renders a single PDF containing one internal-label block per requested
     * order, in the requested order (Req 10.4). Rejects an empty request and any
     * unknown order id.
     *
     * @param orderIds the orders to print labels for (non-empty)
     * @return the combined bulk-label PDF bytes
     */
    @Transactional(readOnly = true)
    public byte[] bulkInternalLabelPdf(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new ValidationException("At least one order id is required for bulk label printing.");
        }
        LabelCompany company = company();
        List<InternalLabelContent> contents = new ArrayList<>(orderIds.size());
        for (Long id : orderIds) {
            OrderEntity order = requireOrder(id);
            CourierInfo courier = courierInfoFor(order);
            contents.add(contentBuilder.buildInternal(order, company, courier.name(), courier.awb()));
        }
        return pdfRenderer.render(contents, logoPng());
    }

    // --- Internal helpers ---------------------------------------------------

    private OrderEntity requireOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
    }

    /**
     * Applies a transition using the shared state machine and mirrors the result
     * onto the aggregate: the new status plus a single status-history row (Req
     * 8.3, 8.4). Illegal transitions throw and leave the order unchanged.
     */
    private void applyTransition(OrderEntity order, OrderStatus target, String actor) {
        OrderStatusLifecycle lifecycle = new OrderStatusLifecycle(order.getOrderStatus());
        StatusHistoryEntry entry = stateMachine.transition(lifecycle, target, actor, SOURCE_SYSTEM);
        order.setOrderStatus(entry.toStatus());
        order.addStatusHistory(new OrderStatusHistory(
                entry.fromStatus(), entry.toStatus(), entry.actor(), entry.source()));
    }
}
