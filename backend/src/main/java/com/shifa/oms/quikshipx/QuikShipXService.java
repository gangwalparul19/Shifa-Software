package com.shifa.oms.quikshipx;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.quikshipx.QuikShipXModels.CreatePayload;
import com.shifa.oms.quikshipx.QuikShipXModels.CreateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the two QuikShipX operations that are not courier-assignment:
 * <b>create-order</b> (on punch → their Pending section) and <b>confirm</b> (on
 * admin approval → mirror status to Confirmed). Both run out-of-band from the
 * {@link QuikShipXDrainer} so a slow/unavailable QuikShipX never blocks order
 * entry or approval.
 *
 * <p>The allot-tracking-id and track-order operations run through the existing
 * courier machinery via {@link QuikShipXCourierClient}; this service does not
 * touch them.
 */
@Service
public class QuikShipXService {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXService.class);

    private final QuikShipXProperties properties;
    private final QuikShipXClient client;
    private final QuikShipXPayloadFactory payloadFactory;
    private final OrderShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final AuditService auditService;

    public QuikShipXService(QuikShipXProperties properties,
                            QuikShipXClient client,
                            QuikShipXPayloadFactory payloadFactory,
                            OrderShipmentRepository shipmentRepository,
                            OrderRepository orderRepository,
                            ProductRepository productRepository,
                            AuditService auditService) {
        this.properties = properties;
        this.client = client;
        this.payloadFactory = payloadFactory;
        this.shipmentRepository = shipmentRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.auditService = auditService;
    }

    /**
     * Creates the QuikShipX shipment for a just-punched order (their Pending
     * section) and records an {@link OrderShipment}. Idempotent: a no-op when the
     * integration is off or a shipment already exists.
     *
     * @throws QuikShipXException on a QuikShipX error (the drainer retries)
     */
    @Transactional
    public void createForOrder(Long orderId) {
        if (!properties.isEnabled()) {
            return;
        }
        if (shipmentRepository.existsByOrderId(orderId)) {
            log.debug("QuikShipX shipment already exists for order {} — skipping create", orderId);
            return;
        }
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("QuikShipX create skipped: order {} no longer exists", orderId);
            return;
        }

        CreatePayload payload = payloadFactory.build(order, loadProducts(order));
        CreateResult result = client.createOrder(payload); // throws QuikShipXException on failure

        OrderShipment shipment = new OrderShipment(order.getId(), order.getOrderCode());
        shipment.recordCreated(result.shipperOrderId(), properties.isTestSecret());
        shipmentRepository.save(shipment);

        if (result.shipperOrderId() == null || result.shipperOrderId().isBlank()) {
            log.warn("QuikShipX created order {} but returned no shipper order id; a tracking id "
                    + "cannot be allotted until it is known (pin the response key)", order.getOrderCode());
        }
        auditService.record(null, "SYSTEM", AuditActions.QUIKSHIPX_PUBLISHED,
                AuditActions.ENTITY_ORDER, String.valueOf(order.getId()),
                "Published order " + order.getOrderCode() + " to QuikShipX (Pending"
                        + (properties.isTestSecret() ? ", TEST" : "") + ")");
        log.info("Published order {} to QuikShipX (shipperOrderId={}, test={})",
                order.getOrderCode(), result.shipperOrderId(), properties.isTestSecret());
    }

    /**
     * Mirrors the QuikShipX status to Confirmed when an order is admin-approved.
     *
     * <p>QuikShipX does not (yet) document a confirm endpoint, so this updates the
     * local mirror only; when {@code app.quikshipx.confirm-order-path} and a
     * confirm operation become available they can be pushed here. If the shipment
     * has not been created yet (create still draining), a {@link QuikShipXException}
     * asks the drainer to retry until it exists.
     */
    @Transactional
    public void confirmForOrder(Long orderId) {
        if (!properties.isEnabled()) {
            return;
        }
        OrderShipment shipment = shipmentRepository.findByOrderId(orderId).orElse(null);
        if (shipment == null) {
            throw new QuikShipXException(
                    "QuikShipX shipment for order " + orderId + " not created yet; will retry confirm", true);
        }
        shipment.setQuikShipXStatus(OrderShipment.STATUS_CONFIRMED);
        shipmentRepository.save(shipment);
        auditService.record(null, "SYSTEM", AuditActions.QUIKSHIPX_CONFIRMED,
                AuditActions.ENTITY_ORDER, String.valueOf(orderId),
                "Mirrored QuikShipX status to Confirmed for order " + shipment.getOrderCode());
        log.info("Mirrored QuikShipX status to Confirmed for order {}", shipment.getOrderCode());
    }

    /** Batch-loads the products referenced by the order's lines (for SKU), avoiding N+1. */
    private Map<Long, Product> loadProducts(OrderEntity order) {
        List<Long> ids = order.getLineItems().stream()
                .map(OrderLineItem::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Product> byId = new HashMap<>();
        for (Product product : productRepository.findAllById(ids)) {
            byId.put(product.getId(), product);
        }
        return byId;
    }
}
