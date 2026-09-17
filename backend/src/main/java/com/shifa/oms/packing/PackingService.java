package com.shifa.oms.packing;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.order.Actor;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.order.RtoReason;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.packing.dto.HandoverRequest;
import com.shifa.oms.packing.dto.MarkRtoRequest;
import com.shifa.oms.packing.dto.PackingQueueResponse;
import com.shifa.oms.packing.dto.PackingQueueRow;
import com.shifa.oms.packing.dto.PackingScanPreviewResponse;
import com.shifa.oms.packing.dto.PackingScanResponse;
import com.shifa.oms.packing.dto.PickListResponse;
import com.shifa.oms.packing.dto.RtoScanPreviewResponse;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.statemachine.OrderStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Packing application service (design: "Order / OMS Module" — packing scan;
 * Req 11).
 *
 * <p>A packing user scans an order's internal-label barcode (whose value is the
 * order's {@code order_code}). {@link #scan(String, String)} resolves that code
 * to an order and applies one of three outcomes:
 * <ul>
 *   <li><strong>Packed</strong> (Req 11.1): the order is in
 *       {@code Label_Generated}, so it transitions to {@code Packed} through the
 *       shared {@link OrderStatusStateMachine} (actor = the packing user, source
 *       {@code PACKING}), writing one {@code status_history} row (Req 8.4). An
 *       {@code ORDER_PACKED} outbox event is persisted in the same transaction so
 *       the admin real-time notification (Req 11.2) is delivered by task 19 and
 *       never lost even if no admin is currently connected.</li>
 *   <li><strong>Not recognized</strong> (Req 11.3): no order matches the barcode
 *       — {@link BarcodeNotRecognizedException} (404).</li>
 *   <li><strong>Wrong status</strong> (Req 11.4): the order exists but is not in
 *       {@code Label_Generated} — {@link OrderNotPackableException} (409) carrying
 *       the current status, and the order is left unchanged.</li>
 * </ul>
 *
 * <p>The wrong-status case is enforced here (rather than relying solely on the
 * state machine) so the rejection can report the current status precisely; the
 * state machine would also reject the transition, but this gives the packer a
 * clearer, status-aware error.
 */
@Service
public class PackingService {

    private static final Logger log = LoggerFactory.getLogger(PackingService.class);

    private static final String SOURCE_PACKING = "PACKING";

    /** Statuses from which a manual RTO mark is a legal move (mirrors the RTO edges in the state machine). */
    private static final java.util.Set<OrderStatus> RTO_ELIGIBLE_STATUSES = java.util.EnumSet.of(
            OrderStatus.COURIER_ASSIGNED, OrderStatus.DISPATCHED,
            OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY);

    private final OrderRepository orderRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final OrderWorkflowService orderWorkflowService;
    private final UserRepository userRepository;
    /**
     * Optional QuikShipX shipment repository so a scanned barcode can be resolved
     * by the QuikShipX order id / AWB printed on the label (nullable in tests /
     * when the integration isn't wired). See {@link #resolveBarcode(String)}.
     */
    private final com.shifa.oms.quikshipx.OrderShipmentRepository orderShipmentRepository;

    /**
     * Optional product repository so the pick-list can look up SKUs for the
     * products it aggregates (nullable in tests / lightweight call sites; the
     * pick-list simply omits the SKU when unavailable). See {@link #pickList()}.
     */
    private final ProductRepository productRepository;

    /**
     * Optional generic/in-house courier record repository so a scanned barcode
     * can also be resolved by a delivery partner's AWB (label redesign feature —
     * single-barcode label: a courier barcode is what actually gets printed and
     * scanned when a partner is assigned, so the RTO/packing scan flow must
     * recognise it, not just our own order code / QuikShipX). Nullable in tests
     * / lightweight call sites. See {@link #resolveBarcodeMatch(String)}.
     */
    private final com.shifa.oms.courier.CourierRecordRepository courierRecordRepository;

    /** Resolves a matched {@code CourierRecord}'s company id to a display name. Nullable in tests. */
    private final com.shifa.oms.courier.CourierCompanyRepository courierCompanyRepository;

    /**
     * Optional returns service so marking an order RTO also raises a sales-return
     * record for GST/CA reporting (GSTR-1 credit notes): nullable in tests /
     * lightweight call sites, in which case {@link #markRto} simply skips
     * creating a return (behaviour-preserving for existing callers/tests).
     */
    private final com.shifa.oms.returns.ReturnService returnService;

    /** Test-friendly constructor without the QuikShipX shipment lookup (order-code scan only). */
    public PackingService(OrderRepository orderRepository, OutboxEventPublisher outboxEventPublisher,
                          OrderWorkflowService orderWorkflowService, UserRepository userRepository) {
        this(orderRepository, outboxEventPublisher, orderWorkflowService, userRepository, null, null);
    }

    /**
     * Constructor used by existing test call sites (5 args): wires the QuikShipX
     * shipment lookup only, leaving the pick-list's product/SKU lookup and the
     * generic courier-AWB lookup unwired (pick-list simply omits SKUs; a generic
     * courier AWB scan falls back to "not recognized").
     */
    public PackingService(OrderRepository orderRepository, OutboxEventPublisher outboxEventPublisher,
                          OrderWorkflowService orderWorkflowService, UserRepository userRepository,
                          com.shifa.oms.quikshipx.OrderShipmentRepository orderShipmentRepository) {
        this(orderRepository, outboxEventPublisher, orderWorkflowService, userRepository,
                orderShipmentRepository, null);
    }

    /** Constructor used by existing test call sites (6 args): leaves the generic courier-AWB lookup unwired. */
    public PackingService(OrderRepository orderRepository, OutboxEventPublisher outboxEventPublisher,
                          OrderWorkflowService orderWorkflowService, UserRepository userRepository,
                          com.shifa.oms.quikshipx.OrderShipmentRepository orderShipmentRepository,
                          ProductRepository productRepository) {
        this(orderRepository, outboxEventPublisher, orderWorkflowService, userRepository,
                orderShipmentRepository, productRepository, null, null);
    }

    /**
     * Constructor used by existing test call sites (8 args): leaves the
     * auto-return-on-RTO orchestration unwired (marking RTO then simply skips
     * creating a return record).
     */
    public PackingService(OrderRepository orderRepository, OutboxEventPublisher outboxEventPublisher,
                          OrderWorkflowService orderWorkflowService, UserRepository userRepository,
                          com.shifa.oms.quikshipx.OrderShipmentRepository orderShipmentRepository,
                          ProductRepository productRepository,
                          com.shifa.oms.courier.CourierRecordRepository courierRecordRepository,
                          com.shifa.oms.courier.CourierCompanyRepository courierCompanyRepository) {
        this(orderRepository, outboxEventPublisher, orderWorkflowService, userRepository,
                orderShipmentRepository, productRepository, courierRecordRepository,
                courierCompanyRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PackingService(OrderRepository orderRepository, OutboxEventPublisher outboxEventPublisher,
                          OrderWorkflowService orderWorkflowService, UserRepository userRepository,
                          com.shifa.oms.quikshipx.OrderShipmentRepository orderShipmentRepository,
                          ProductRepository productRepository,
                          com.shifa.oms.courier.CourierRecordRepository courierRecordRepository,
                          com.shifa.oms.courier.CourierCompanyRepository courierCompanyRepository,
                          com.shifa.oms.returns.ReturnService returnService) {
        this.orderRepository = orderRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.orderWorkflowService = orderWorkflowService;
        this.userRepository = userRepository;
        this.orderShipmentRepository = orderShipmentRepository;
        this.productRepository = productRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
        this.returnService = returnService;
    }

    /**
     * The packing team's work queues (oldest-first): orders awaiting packing
     * ({@code Label_Generated}), awaiting handover ({@code Packed}), and awaiting
     * dispatch ({@code Handed_To_Delivery}). Read-only; drives the Packing page
     * so the packer can see what to work on and reprint labels.
     */
    @Transactional(readOnly = true)
    public PackingQueueResponse queue() {
        List<OrderEntity> pack = orderRepository.findByOrderStatusOrderByCreatedAtDesc(OrderStatus.LABEL_GENERATED);
        List<OrderEntity> handover = orderRepository.findByOrderStatusOrderByCreatedAtDesc(OrderStatus.PACKED);
        List<OrderEntity> dispatch = orderRepository.findByOrderStatusOrderByCreatedAtDesc(OrderStatus.HANDED_TO_DELIVERY);
        // Batch-resolve salesperson (created_by) names once for all three queues,
        // mirroring the reporting module's name resolution (full name, else username).
        Map<Long, String> names = resolveSalespersonNames(pack, handover, dispatch);
        return new PackingQueueResponse(rows(pack, names), rows(handover, names), rows(dispatch, names));
    }

    private Map<Long, String> resolveSalespersonNames(List<OrderEntity> pack,
                                                      List<OrderEntity> handover,
                                                      List<OrderEntity> dispatch) {
        Set<Long> ids = new HashSet<>();
        collectCreators(pack, ids);
        collectCreators(handover, ids);
        collectCreators(dispatch, ids);
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (User u : userRepository.findAllById(ids)) {
            String name = (u.getFullName() != null && !u.getFullName().isBlank())
                    ? u.getFullName() : u.getUsername();
            names.put(u.getId(), name);
        }
        return names;
    }

    private static void collectCreators(List<OrderEntity> orders, Set<Long> ids) {
        for (OrderEntity o : orders) {
            if (o.getCreatedBy() != null) {
                ids.add(o.getCreatedBy());
            }
        }
    }

    private static List<PackingQueueRow> rows(List<OrderEntity> orders, Map<Long, String> names) {
        return orders.stream()
                .map(o -> PackingQueueRow.from(
                        o, o.getCreatedBy() == null ? null : names.get(o.getCreatedBy())))
                .toList();
    }

    /**
     * Resolves a scanned internal-label barcode without changing the order. The
     * caller uses the returned next action to present confirmation before making
     * the corresponding authorised workflow request.
     *
     * @throws BarcodeNotRecognizedException when no order matches the barcode
     */
    @Transactional(readOnly = true)
    public PackingScanPreviewResponse preview(String barcode) {
        return PackingScanPreviewResponse.from(resolveBarcodeMatch(barcode).order());
    }

    /**
     * Scans a barcode and, when valid, moves the matching labelled order to
     * {@code Packed}. The final mutation deliberately rechecks the current state
     * because a preview is not a reservation.
     *
     * @param barcode the scanned barcode value (the order's {@code order_code})
     * @param actor   the packing user (or admin) performing the scan (recorded as actor,
     *                and authorized for the transition by role)
     * @return the success response with the packed order summary (Req 11.1)
     * @throws BarcodeNotRecognizedException when no order matches (Req 11.3)
     * @throws OrderNotPackableException when the order is not {@code Label_Generated} (Req 11.4)
     */
    @Transactional
    public PackingScanResponse scan(String barcode, AuthPrincipal actor) {
        OrderEntity order = resolveBarcodeMatch(barcode).order();

        if (order.getOrderStatus() != OrderStatus.LABEL_GENERATED) {
            // Recheck after preview and surface the current status without mutation.
            throw new OrderNotPackableException(order.getOrderCode(), order.getOrderStatus());
        }

        // Centralized transition: authorize (role) → apply (409 on illegal) →
        // status + one history row → audit, all in OrderWorkflowService.
        orderWorkflowService.applyTransition(
                order, OrderStatus.PACKED, Actor.user(actor, SOURCE_PACKING));
        OrderEntity saved = orderRepository.save(order);

        // Persist the packed event for the admin real-time notification (Req 11.2,
        // delivered by task 19). Same transaction → event and status commit together.
        outboxEventPublisher.publishOrderPacked(
                saved.getId(), saved.getOrderCode(), saved.getCustomerName(), actor.username());

        // Courier assignment is no longer enqueued here: under the role-based
        // workflow the COURIER_ASSIGN event is published by the dispatch action
        // (from Handed_To_Delivery), not immediately on pack (design §4, §6.3).
        log.debug("Order {} scanned to PACKED by {}", saved.getOrderCode(), actor.username());
        return PackingScanResponse.packed(saved);
    }

    /**
     * The result of resolving a scanned barcode: the matched order plus whether
     * the match came from a delivery partner's own barcode (QuikShipX order
     * id/AWB, or a generic/in-house courier AWB) rather than our own order code —
     * and, when so, the partner's display name + the AWB value that was scanned
     * (label redesign feature: single-barcode label). Lets the RTO scan page
     * show "scanned via <partner> — AWB <value>" per the printed barcode.
     */
    private record BarcodeMatch(OrderEntity order, boolean viaCourier, String courierName, String courierAwb) {
        static BarcodeMatch direct(OrderEntity order) {
            return new BarcodeMatch(order, false, null, null);
        }

        static BarcodeMatch viaCourier(OrderEntity order, String courierName, String awb) {
            return new BarcodeMatch(order, true, courierName, awb);
        }
    }

    /**
     * Resolves a scanned barcode to an order. Because the redesigned label prints
     * exactly one scannable barcode — the delivery partner's (name + AWB) once
     * one is allotted, else our own order code as a fallback — the packer/RTO
     * scan must resolve whichever one is actually on the parcel. Tried in order:
     * <ol>
     *   <li>our internal {@code order_code} (the fallback label / manual entry);</li>
     *   <li>the QuikShipX order id printed on published labels
     *       ({@code shipper_order_id}), or its AWB;</li>
     *   <li>a generic/in-house {@code CourierRecord}'s AWB (manually assigned
     *       courier).</li>
     * </ol>
     * Only the first match wins; an unmatched code is {@code BARCODE_NOT_RECOGNIZED}.
     */
    private BarcodeMatch resolveBarcodeMatch(String barcode) {
        String code = barcode == null ? "" : barcode.trim();
        java.util.Optional<OrderEntity> byCode = orderRepository.findByOrderCode(code);
        if (byCode.isPresent()) {
            return BarcodeMatch.direct(byCode.get());
        }
        if (orderShipmentRepository != null && !code.isEmpty()) {
            java.util.Optional<com.shifa.oms.quikshipx.OrderShipment> shipment =
                    orderShipmentRepository.findByShipperOrderId(code)
                            .or(() -> orderShipmentRepository.findByAwb(code));
            if (shipment.isPresent()) {
                java.util.Optional<OrderEntity> viaShipment =
                        orderRepository.findById(shipment.get().getOrderId());
                if (viaShipment.isPresent()) {
                    String awb = shipment.get().getAwb() != null ? shipment.get().getAwb() : code;
                    return BarcodeMatch.viaCourier(viaShipment.get(), "QuikShipX", awb);
                }
            }
        }
        if (courierRecordRepository != null && !code.isEmpty()) {
            java.util.Optional<com.shifa.oms.courier.CourierRecord> record =
                    courierRecordRepository.findByAwb(code);
            if (record.isPresent()) {
                java.util.Optional<OrderEntity> viaRecord = orderRepository.findById(record.get().getOrderId());
                if (viaRecord.isPresent()) {
                    String name = null;
                    if (courierCompanyRepository != null && record.get().getCourierCompanyId() != null) {
                        name = courierCompanyRepository.findById(record.get().getCourierCompanyId())
                                .map(com.shifa.oms.courier.CourierCompany::getName)
                                .orElse(null);
                    }
                    return BarcodeMatch.viaCourier(viaRecord.get(), name, code);
                }
            }
        }
        throw new BarcodeNotRecognizedException(code);
    }

    // --- Manual RTO marking (label redesign feature) -----------------------

    /**
     * Resolves a scanned order-label barcode for the RTO page without changing
     * the order, reporting whether marking it RTO is currently a legal move
     * (Req: manual RTO marking). Reuses the same barcode resolution as the
     * packing scan ({@link #resolveBarcode(String)}) so either the courier
     * barcode (AWB) or the order barcode on the redesigned label resolves.
     *
     * @throws BarcodeNotRecognizedException when no order matches the barcode
     */
    @Transactional(readOnly = true)
    public RtoScanPreviewResponse rtoPreview(String barcode) {
        BarcodeMatch match = resolveBarcodeMatch(barcode);
        return RtoScanPreviewResponse.from(match.order(), match.viaCourier(), match.courierName(), match.courierAwb());
    }

    /**
     * Marks an order RTO (returned to origin) after an explicit scan +
     * confirmation on the RTO page, recording the categorized reason (+
     * optional note) that a courier webhook/poll driven RTO does not capture.
     * The final mutation deliberately rechecks the current state because a
     * preview is not a reservation.
     *
     * @param orderId the order to mark RTO
     * @param request the categorized reason + optional note
     * @param actor   the packing user (or admin) performing the mark
     * @return the updated order projection (now {@code RTO})
     * @throws ResourceNotFoundException when no order matches (404)
     * @throws OrderNotRtoEligibleException when the order is not in an RTO-eligible status (409)
     */
    @Transactional
    public OrderResponse markRto(Long orderId, MarkRtoRequest request, AuthPrincipal actor) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        if (!RTO_ELIGIBLE_STATUSES.contains(order.getOrderStatus())) {
            // Recheck after preview and surface the current status without mutation.
            throw new OrderNotRtoEligibleException(order.getOrderCode(), order.getOrderStatus());
        }

        RtoReason reason = request.reason();
        String note = trimToNull(request.note());
        order.setRtoReason(reason, note);

        // Centralized transition: authorize (role) → apply (409 on illegal) →
        // status + one history row → audit → matrix notification.
        orderWorkflowService.applyTransition(
                order, OrderStatus.RTO, Actor.user(actor, SOURCE_PACKING));
        OrderEntity saved = orderRepository.save(order);

        // Auto-raise a sales-return record for GST/CA reporting (client request:
        // RTO should surface as a sales return in the GSTR-1 credit-note figures).
        // Best-effort/non-fatal: the RTO status change itself must never fail
        // because the return bookkeeping couldn't be created.
        if (returnService != null) {
            try {
                returnService.createAutoReturnForRto(saved.getId(), reason != null ? reason.name() : note);
            } catch (RuntimeException e) {
                log.warn("Could not auto-create a sales return for RTO order {}: {}",
                        saved.getOrderCode(), e.getMessage());
            }
        }

        log.debug("Order {} manually marked RTO by {} (reason {})",
                saved.getOrderCode(), actor.username(), reason);
        return OrderResponse.from(saved);
    }

    /**
     * Hands a packed order over to the delivery courier: transitions
     * {@code PACKED → HANDED_TO_DELIVERY} through the shared
     * {@link OrderWorkflowService} (Req 9.2, 9.3), recording one
     * {@code status_history} row and an audit entry (Req 9.6) and firing the
     * {@code HANDED_TO_DELIVERY} matrix notification (ADMIN in-app, Req 9.7).
     *
     * @param orderId the order to hand over
     * @param actor   the packing user (or admin) performing the handover
     * @return the updated order projection (now {@code HANDED_TO_DELIVERY})
     * @throws ResourceNotFoundException when no order matches (404)
     * @throws OrderNotHandoverableException when the order is not {@code PACKED} (409)
     */
    @Transactional
    public OrderResponse handover(Long orderId, AuthPrincipal actor, HandoverRequest request) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        if (order.getOrderStatus() != OrderStatus.PACKED) {
            // Surface the current status without mutating the order (Req 9.4).
            throw new OrderNotHandoverableException(order.getOrderCode(), order.getOrderStatus());
        }

        // Capture who the order was handed to (product-audit §4.3), when supplied,
        // plus the optional in-house vehicle / transport reference (V63) — for an
        // in-house delivery there is no courier AWB, so the handover name +
        // vehicle number are what identify the shipment.
        if (request != null) {
            order.setHandoverDetails(
                    trimToNull(request.handoverName()), trimToNull(request.handoverPhone()));
            String vehicle = trimToNull(request.vehicleNumber());
            if (vehicle != null) {
                order.setVehicleNumber(vehicle);
            }
        }

        // Centralized transition: authorize (role) → apply (409 on illegal) →
        // status + one history row → audit → matrix notification.
        orderWorkflowService.applyTransition(
                order, OrderStatus.HANDED_TO_DELIVERY, Actor.user(actor, SOURCE_PACKING));
        OrderEntity saved = orderRepository.save(order);

        log.debug("Order {} handed to delivery by {}", saved.getOrderCode(), actor.username());
        return OrderResponse.from(saved);
    }

    /**
     * Dispatches a handed-over order by enqueuing the {@code COURIER_ASSIGN}
     * outbox event (Req 10.1). The {@link com.shifa.oms.courier.OutboxCourierDrainer}
     * → {@code CourierAssignmentService.assignForOrder} (gated on
     * {@code HANDED_TO_DELIVERY}) then requests the AWB/label out-of-band and
     * advances {@code HANDED_TO_DELIVERY → COURIER_ASSIGNED}, so a slow courier
     * never blocks the dispatch action. The order itself is left in
     * {@code HANDED_TO_DELIVERY} synchronously.
     *
     * @param orderId the order to dispatch
     * @param actor   the packing user (or admin) performing the dispatch
     * @return the order projection (still {@code HANDED_TO_DELIVERY})
     * @throws ResourceNotFoundException when no order matches (404)
     * @throws OrderNotDispatchableException when the order is not {@code HANDED_TO_DELIVERY} (409)
     */
    @Transactional
    public OrderResponse dispatch(Long orderId, AuthPrincipal actor) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));

        if (order.getOrderStatus() != OrderStatus.HANDED_TO_DELIVERY) {
            // Surface the current status without mutating the order.
            throw new OrderNotDispatchableException(order.getOrderCode(), order.getOrderStatus());
        }

        // Enqueue courier AWB assignment out-of-band (Req 10.1): the drainer picks
        // this PENDING event up and calls the Courier API in its own transaction.
        outboxEventPublisher.publishCourierAssign(order.getId(), order.getOrderCode());

        log.debug("Order {} dispatched (courier assignment enqueued) by {}",
                order.getOrderCode(), actor.username());
        return OrderResponse.from(order);
    }

    /**
     * The daily pick-list / packing manifest (enhancement): aggregates every
     * product needed across all orders awaiting packing ({@code Label_Generated})
     * into one sheet, sorted by total quantity descending — the packer picks the
     * highest-volume items first, once per product, instead of walking the stock
     * room per order.
     */
    @Transactional(readOnly = true)
    public PickListResponse pickList() {
        List<OrderEntity> orders = orderRepository.findByOrderStatusOrderByCreatedAtDesc(OrderStatus.LABEL_GENERATED);

        // Aggregate quantity + distinct-order-count per product id (falling back to
        // the snapshotted product name as the key for line-less/legacy products
        // with a null productId, so they still surface on the sheet).
        Map<Object, Integer> qtyByKey = new java.util.LinkedHashMap<>();
        Map<Object, Integer> orderCountByKey = new java.util.LinkedHashMap<>();
        Map<Object, String> nameByKey = new java.util.LinkedHashMap<>();
        for (OrderEntity order : orders) {
            Set<Object> keysInThisOrder = new HashSet<>();
            for (var item : order.getLineItems()) {
                Object key = item.getProductId() != null ? item.getProductId() : item.getProductName();
                qtyByKey.merge(key, item.getQuantity(), Integer::sum);
                nameByKey.putIfAbsent(key, item.getProductName());
                if (keysInThisOrder.add(key)) {
                    orderCountByKey.merge(key, 1, Integer::sum);
                }
            }
        }

        Map<Long, String> skusByProductId = resolveSkus(qtyByKey.keySet());

        List<PickListResponse.PickListLine> lines = qtyByKey.entrySet().stream()
                .map(e -> {
                    Object key = e.getKey();
                    Long productId = key instanceof Long id ? id : null;
                    return new PickListResponse.PickListLine(
                            productId,
                            nameByKey.get(key),
                            productId != null ? skusByProductId.get(productId) : null,
                            e.getValue(),
                            orderCountByKey.getOrDefault(key, 0));
                })
                .sorted(java.util.Comparator.comparingInt(PickListResponse.PickListLine::totalQuantity).reversed())
                .toList();

        return new PickListResponse(orders.size(), lines);
    }

    private Map<Long, String> resolveSkus(Set<Object> keys) {
        if (productRepository == null) {
            return Map.of();
        }
        List<Long> productIds = keys.stream()
                .filter(Long.class::isInstance)
                .map(Long.class::cast)
                .toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> skus = new HashMap<>();
        for (Product p : productRepository.findAllById(productIds)) {
            skus.put(p.getId(), p.getSku());
        }
        return skus;
    }

    /**
     * Sets how many boxes an order ships in (product-audit §4.2 — multi-pack).
     * Additive: it only records the count so the label print can produce one
     * copy per box; the order status is unchanged.
     *
     * @param orderId the order to update
     * @param count   the number of boxes (1–50, validated at the DTO layer)
     * @return the updated order projection
     */
    @Transactional
    public OrderResponse setPackageCount(Long orderId, int count) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));
        order.setPackageCount(count);
        OrderEntity saved = orderRepository.save(order);
        log.debug("Order {} package count set to {}", saved.getOrderCode(), saved.getPackageCount());
        return OrderResponse.from(saved);
    }

    /** Trims a string and returns null when the result is empty/blank. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
