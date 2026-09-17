package com.shifa.oms.courier;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.courier.dto.TrackingResponse;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Builds the public customer tracking view for an order (Req 13.4): current
 * status plus AWB and courier tracking link once a courier is assigned.
 */
@Service
public class TrackingService {

    private final OrderRepository orderRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final CourierCompanyRepository courierCompanyRepository;

    public TrackingService(OrderRepository orderRepository,
                           CourierRecordRepository courierRecordRepository,
                           CourierCompanyRepository courierCompanyRepository) {
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
    }

    /**
     * Tracking details for an order by its code (Req 13.4).
     *
     * @param orderCode the order code
     * @return the tracking projection
     * @throws ResourceNotFoundException when no order has that code
     */
    @Transactional(readOnly = true)
    public TrackingResponse track(String orderCode) {
        OrderEntity order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No order found for code " + orderCode + "."));

        ShipmentInfo shipment = shipmentFor(order.getId()).orElse(null);
        if (shipment == null) {
            return new TrackingResponse(order.getOrderCode(), order.getOrderStatus(), null, null, null);
        }
        return new TrackingResponse(order.getOrderCode(), order.getOrderStatus(),
                shipment.awb(), shipment.courierName(), shipment.trackingUrl());
    }

    /**
     * Builds the {@link ShipmentInfo} for an order id, reusing the same
     * courier-record lookup and tracking-link building as the public tracking
     * view (Req 13.4). Returns {@link Optional#empty()} when no courier record
     * with an AWB exists for the order, so the caller can leave shipment fields
     * unset. Shared by the admin order-detail response.
     */
    @Transactional(readOnly = true)
    public Optional<ShipmentInfo> shipmentFor(Long orderId) {
        Optional<CourierRecord> record = courierRecordRepository.findByOrderId(orderId);
        if (record.isEmpty() || record.get().getAwb() == null || record.get().getAwb().isBlank()) {
            return Optional.empty();
        }

        CourierRecord cr = record.get();
        String courierName = null;
        String trackingUrl = null;
        if (cr.getCourierCompanyId() != null) {
            Optional<CourierCompany> company = courierCompanyRepository.findById(cr.getCourierCompanyId());
            if (company.isPresent()) {
                courierName = company.get().getName();
                trackingUrl = company.get().trackingUrl(cr.getAwb());
            }
        }
        return Optional.of(new ShipmentInfo(
                cr.getAwb(), courierName, trackingUrl, cr.getEstimatedDelivery()));
    }

    /**
     * The assigned delivery partner's name for an order, <em>independent of
     * whether an AWB exists</em> (in-house-delivery feature).
     *
     * <p>{@link #shipmentFor} deliberately returns empty without an AWB (the
     * public tracking view has nothing to track), but a partner can now be
     * recorded with no tracking number at all — e.g. a parcel handed to a local
     * operator or sent by bus. This lets the admin order-detail view still show
     * who has the parcel. Returns {@link Optional#empty()} when no courier record
     * / company is recorded for the order.
     */
    @Transactional(readOnly = true)
    public Optional<String> courierNameFor(Long orderId) {
        return courierRecordRepository.findByOrderId(orderId)
                .map(CourierRecord::getCourierCompanyId)
                .flatMap(companyId -> companyId == null
                        ? Optional.empty() : courierCompanyRepository.findById(companyId))
                .map(CourierCompany::getName);
    }
}
