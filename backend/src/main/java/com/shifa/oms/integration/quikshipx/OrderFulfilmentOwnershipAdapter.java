package com.shifa.oms.integration.quikshipx;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderFulfilmentOwnership;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link OrderFulfilmentOwnership} from the presence of a QuikShipX shipment
 * record (Req 9.1, 9.3, 9.10).
 *
 * <p>Two short-circuits before touching the database, both of which matter because this
 * is consulted on every transition and every packing-queue read:
 *
 * <ul>
 *   <li><b>Integration disabled</b> — nothing is managed, so the whole feature is a
 *       no-op and no query is issued at all (Req 13.2, 15.7).</li>
 *   <li><b>Fallback mode</b> — an admin has taken this order back, so it is not managed
 *       even though a shipment exists (Req 9.3).</li>
 * </ul>
 */
@Component
public class OrderFulfilmentOwnershipAdapter implements OrderFulfilmentOwnership {

    private final QuikShipXProperties properties;
    private final OrderShipmentRepository shipmentRepository;

    public OrderFulfilmentOwnershipAdapter(QuikShipXProperties properties,
                                           OrderShipmentRepository shipmentRepository) {
        this.properties = properties;
        this.shipmentRepository = shipmentRepository;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public boolean isCourierManaged(OrderEntity order) {
        if (order == null || order.getId() == null) {
            // An unsaved order cannot have a shipment.
            return false;
        }
        if (!properties.isEnabled()) {
            return false;
        }
        if (order.isFallbackMode()) {
            return false;
        }
        return shipmentRepository.existsByOrderId(order.getId());
    }
}
