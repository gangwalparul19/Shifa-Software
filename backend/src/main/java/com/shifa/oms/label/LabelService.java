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
    private final LabelContentBuilder contentBuilder;
    private final LabelPdfRenderer pdfRenderer;
    private final OrderStatusStateMachine stateMachine;

    /** Test-friendly constructor without the company collaborators (no logo/seller on labels). */
    public LabelService(OrderRepository orderRepository, StorageService storageService) {
        this(orderRepository, storageService, null, null);
    }

    /** Constructor with the logo collaborator only (kept for callers that don't wire settings). */
    public LabelService(OrderRepository orderRepository, StorageService storageService,
                        com.shifa.oms.settings.CompanyLogoService companyLogoService) {
        this(orderRepository, storageService, companyLogoService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LabelService(OrderRepository orderRepository, StorageService storageService,
                        com.shifa.oms.settings.CompanyLogoService companyLogoService,
                        com.shifa.oms.settings.SettingsService settingsService) {
        this.orderRepository = orderRepository;
        this.storageService = storageService;
        this.companyLogoService = companyLogoService;
        this.settingsService = settingsService;
        this.contentBuilder = new LabelContentBuilder();
        this.pdfRenderer = new LabelPdfRenderer(new BarcodeGenerator());
        this.stateMachine = new OrderStatusStateMachine();
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
            return new LabelCompany(brand, brand, pickup.isBlank() ? null : pickup);
        } catch (Exception e) {
            log.debug("Falling back to default label company (settings unavailable): {}", e.getMessage());
            return LabelCompany.defaults();
        }
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
        InternalLabelContent content = contentBuilder.buildInternal(order, company());
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
        InternalLabelContent content = contentBuilder.buildInternal(order, company());
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
        List<OrderEntity> orders = new ArrayList<>(orderIds.size());
        for (Long id : orderIds) {
            orders.add(requireOrder(id));
        }
        List<InternalLabelContent> contents = contentBuilder.buildBulk(orders, company());
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
