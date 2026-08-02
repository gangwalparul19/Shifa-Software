package com.shifa.oms.integration.quikshipx;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderFulfilmentPublisher;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides at approval time whether QuikShipX takes over fulfilment, and if so queues the
 * publication (Req 5.1, 5.8, 5.9).
 *
 * <p>Runs inside the approval transaction, so an approval cannot commit without its
 * publication being queued alongside it — no window where an order is approved but
 * invisible to the drainer.
 *
 * <p>The eligibility rules read as a series of reasons to decline, each of which would
 * otherwise cause a real operational problem:
 *
 * <ul>
 *   <li><b>Integration disabled</b> — Fallback_Mode is switched on so the order commits
 *       to the internal path rather than being re-evaluated later (Req 5.8).</li>
 *   <li><b>Already in Fallback_Mode</b> — an admin has deliberately taken this order
 *       back; publishing would undo that.</li>
 *   <li><b>Channel is {@code SHOPIFY_API}</b> — QuikShipX already has the order from the
 *       storefront integration, so re-submitting would duplicate the shipment
 *       (Req 5.9).</li>
 *   <li><b>Shipment already exists</b> — the order has been published before
 *       (Req 5.10).</li>
 * </ul>
 */
@Component
public class QuikShipXPublicationGateway implements OrderFulfilmentPublisher {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXPublicationGateway.class);

    private final QuikShipXProperties properties;
    private final OrderShipmentRepository shipmentRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    public QuikShipXPublicationGateway(QuikShipXProperties properties,
                                       OrderShipmentRepository shipmentRepository,
                                       OutboxEventPublisher outboxEventPublisher) {
        this.properties = properties;
        this.shipmentRepository = shipmentRepository;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Override
    public boolean publishOnApproval(OrderEntity order) {
        if (!properties.isEnabled()) {
            // Commit this order to internal fulfilment so it is unambiguous even if the
            // integration is switched on tomorrow (Req 5.8).
            order.setFallbackMode(true);
            return false;
        }
        if (order.isFallbackMode()) {
            return false;
        }
        if (order.getSource() == null || order.getSource().canonical() != OrderSource.SHIFA_ADMIN) {
            // Shopify orders reach QuikShipX from the storefront, never from us.
            return false;
        }
        if (order.getId() != null && shipmentRepository.existsByOrderId(order.getId())) {
            log.debug("Order {} already has a QuikShipX shipment; not re-publishing", order.getOrderCode());
            return false;
        }

        outboxEventPublisher.publishQuikShipXPublish(order.getId(), order.getOrderCode());
        log.info("Queued QuikShipX publication for order {}", order.getOrderCode());
        return true;
    }
}
