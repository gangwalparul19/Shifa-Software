package com.shifa.oms.integration.quikshipx;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.integration.IntegrationOutcome;
import com.shifa.oms.integration.IntegrationEventStore;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.SettingsService;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Submits an approved {@code SHIFA_ADMIN} order to QuikShipX and records the resulting
 * shipment (Req 5.1–5.11, 16.5, 16.6).
 *
 * <p>Called from {@link QuikShipXPublishDrainer}, one order per transaction, so a
 * poisoned order cannot stall the queue.
 *
 * <p>Every guard here is re-checked at submission time rather than trusted from approval
 * time, because minutes may pass between the two: an admin could have enabled
 * Fallback_Mode, the integration could have been switched off, or a concurrent retry
 * could already have published.
 */
@Service
public class QuikShipXPublisher {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXPublisher.class);

    private final OrderRepository orderRepository;
    private final OrderShipmentRepository shipmentRepository;
    private final ProductRepository productRepository;
    private final SettingsService settingsService;
    private final QuikShipXClient client;
    private final QuikShipXProperties properties;
    private final IntegrationEventStore eventStore;
    private final AuditService auditService;

    public QuikShipXPublisher(OrderRepository orderRepository,
                              OrderShipmentRepository shipmentRepository,
                              ProductRepository productRepository,
                              SettingsService settingsService,
                              QuikShipXClient client,
                              QuikShipXProperties properties,
                              IntegrationEventStore eventStore,
                              AuditService auditService) {
        this.orderRepository = orderRepository;
        this.shipmentRepository = shipmentRepository;
        this.productRepository = productRepository;
        this.settingsService = settingsService;
        this.client = client;
        this.properties = properties;
        this.eventStore = eventStore;
        this.auditService = auditService;
    }

    /** Why a publication attempt did not result in a new shipment. */
    public enum Skip {
        /** Published successfully. */
        NONE,
        ORDER_MISSING,
        INTEGRATION_DISABLED,
        FALLBACK_MODE,
        WRONG_CHANNEL,
        NOT_ADMIN_APPROVED,
        ALREADY_PUBLISHED,
        SHIPMENT_DEFAULTS_INCOMPLETE,
        CREDENTIALS_MISSING
    }

    /** The outcome of one publication attempt. */
    public record Result(Skip skip, String detail) {

        public boolean published() {
            return skip == Skip.NONE;
        }

        /**
         * Whether the drainer should keep retrying. A skip is a settled decision, not a
         * transient failure, so retrying it would just burn the ladder.
         */
        public boolean retryable() {
            return false;
        }
    }

    /**
     * Publishes one order.
     *
     * @throws QuikShipXPublicationException when QuikShipX itself failed, so the drainer
     *         can decide between retrying and giving up
     */
    @Transactional
    public Result publish(Long orderId) {
        Optional<OrderEntity> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            return new Result(Skip.ORDER_MISSING, "order " + orderId + " no longer exists");
        }
        OrderEntity order = found.get();

        Result guard = checkGuards(order);
        if (guard != null) {
            log.info("Skipping QuikShipX publication for order {}: {} ({})",
                    order.getOrderCode(), guard.skip(), guard.detail());
            return guard;
        }

        AppSettings settings = settingsService.getSettings();
        ShipmentSubmission submission = ShipmentPayloadFactory.build(
                order, settings, loadProducts(order), properties);

        // Pre-flight: catch every field QuikShipX would reject in one pass, before the
        // network round-trip, so the admin fixes them all at once instead of retry-by-retry
        // (Req 16.6). Not retryable — a missing HSN/category fails identically next time.
        List<String> problems = ShipmentSubmissionValidator.validate(submission);
        if (!problems.isEmpty()) {
            String reason = "Order not ready for QuikShipX: " + String.join(" ", problems);
            log.warn("Pre-flight validation failed for order {}: {}", order.getOrderCode(), problems);
            throw new QuikShipXPublicationException(reason, false, null);
        }

        ShipmentAcceptance acceptance;
        try {
            acceptance = client.createShipment(submission);
        } catch (QuikShipXClientException e) {
            if (e.isAlreadyExists()) {
                // QuikShipX already has this customer_order_id — a concurrent auto-publish
                // won the race, or the order was sent before. That is a success from the
                // business's point of view, not a failure to alert on. Ensure our side has a
                // shipment row (reference-only if the winner hasn't committed yet) and report
                // it as already published.
                if (!shipmentRepository.existsByOrderId(order.getId())) {
                    try {
                        OrderShipment existing = OrderShipment.from(order.getId(),
                                ShipmentAcceptance.referenceOnly(
                                        submission.orderReference(), properties.isTestSecret(), null));
                        existing.advanceStatus(OrderShipment.INITIAL_STATUS, java.time.LocalDateTime.now());
                        shipmentRepository.save(existing);
                    } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
                        // The concurrent winner committed first; its row stands.
                    }
                }
                log.info("QuikShipX publication for order {} is already present ({})",
                        order.getOrderCode(), e.getMessage());
                return new Result(Skip.ALREADY_PUBLISHED, "QuikShipX already has this order");
            }
            // Never include the submission body in the reason: it carries the user secret.
            String reason = e.getMessage()
                    + (e.getRejectedFields().isEmpty()
                            ? "" : " [rejected: " + String.join(", ", e.getRejectedFields()) + "]");
            throw new QuikShipXPublicationException(reason, e.isRetryable(), e);
        }

        OrderShipment shipment = OrderShipment.from(order.getId(), acceptance);
        // QuikShipX has accepted it, so it now sits in their "Pending" section — reflect
        // that as the order's QuikShipX status from this moment (Req: initial status mirror).
        shipment.advanceStatus(OrderShipment.INITIAL_STATUS, java.time.LocalDateTime.now());
        try {
            shipmentRepository.save(shipment);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // A concurrent publish (the drainer and the manual button racing) already saved
            // the UNIQUE(order_id) row. Treat as idempotent rather than surfacing a
            // constraint-violation stack trace.
            log.info("QuikShipX shipment for order {} was saved concurrently; treating as "
                    + "already published", order.getOrderCode());
            return new Result(Skip.ALREADY_PUBLISHED, "a shipment already exists");
        }

        auditService.record(AuditActions.QUIKSHIPX_PUBLISHED, AuditActions.ENTITY_ORDER,
                String.valueOf(order.getId()),
                "Published order " + order.getOrderCode() + " to QuikShipX as "
                        + acceptance.orderReference()
                        + acceptance.shipmentIdValue().map(id -> " (shipment " + id + ")").orElse("")
                        + (acceptance.test() ? " [TEST secret]" : ""));

        log.info("Published order {} to QuikShipX reference={} shipmentId={} awb={} test={}",
                order.getOrderCode(), acceptance.orderReference(),
                acceptance.shipmentIdValue().orElse("-"), acceptance.awbValue().orElse("-"),
                acceptance.test());

        return new Result(Skip.NONE, acceptance.orderReference());
    }

    /**
     * Re-validates eligibility at submission time.
     *
     * @return a skip result, or null when the order is publishable
     */
    private Result checkGuards(OrderEntity order) {
        if (!properties.isEnabled()) {
            return new Result(Skip.INTEGRATION_DISABLED, "app.quikshipx.enabled=false");
        }
        if (order.isFallbackMode()) {
            // An admin took this order back after approval; respect that.
            return new Result(Skip.FALLBACK_MODE, "order is in fallback mode");
        }
        if (order.getSource() == null || order.getSource().canonical() != OrderSource.SHIFA_ADMIN) {
            return new Result(Skip.WRONG_CHANNEL, "channel is " + order.getSource());
        }
        if (shipmentRepository.existsByOrderId(order.getId())) {
            // The UNIQUE(order_id) index would reject the insert anyway; failing here
            // keeps the retry from surfacing as a constraint-violation stack trace.
            return new Result(Skip.ALREADY_PUBLISHED, "a shipment already exists");
        }
        if (!hasAdminApproval(order)) {
            // Req 4.4: a SHIFA_ADMIN order must have been approved by a human ADMIN
            // before it reaches a courier.
            return new Result(Skip.NOT_ADMIN_APPROVED, "no ADMIN transition to APPROVED in history");
        }
        if (!properties.hasCredentials()) {
            return new Result(Skip.CREDENTIALS_MISSING,
                    "missing configuration: " + properties.missingCredentials());
        }
        if (!settingsService.getSettings().shipmentDefaultsComplete()) {
            // Req 16.6: sending a body with no pickup warehouse would be rejected, and
            // guessing a warehouse would ship parcels from the wrong place.
            return new Result(Skip.SHIPMENT_DEFAULTS_INCOMPLETE,
                    "the pickup warehouse id is not configured on the Settings page");
        }
        return null;
    }

    /**
     * Whether a human ADMIN approved this order (Req 4.4). Read from the status history
     * rather than the current status, because the order may have moved on since.
     */
    private boolean hasAdminApproval(OrderEntity order) {
        return order.getStatusHistory().stream().anyMatch(row ->
                row.getToStatus() == OrderStatus.APPROVED
                        && "ADMIN".equalsIgnoreCase(String.valueOf(row.getSource())));
    }

    /** Batch-loads the products referenced by the order's lines, avoiding an N+1. */
    private Map<Long, Product> loadProducts(OrderEntity order) {
        List<Long> productIds = order.getLineItems().stream()
                .map(OrderLineItem::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Product> byId = new HashMap<>();
        for (Product product : productRepository.findAllById(productIds)) {
            byId.put(product.getId(), product);
        }
        return byId;
    }

    /** Records a terminal publication failure against the order (Req 5.7, 5.11). */
    public void recordTerminalFailure(Long orderId, String orderCode, String reason, int attempts) {
        eventStore.recordPublicationFailure(orderCode, orderId,
                IntegrationOutcome.PUBLICATION_FAILED, reason, attempts);
    }
}
