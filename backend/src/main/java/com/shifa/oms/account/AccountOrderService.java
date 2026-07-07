package com.shifa.oms.account;

import com.shifa.oms.account.dto.AccountOrderSummary;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.courier.TrackingService;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.dto.OrderResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A logged-in customer's order history ("My Orders").
 *
 * <p><strong>Association rule:</strong> an order belongs to a customer when its
 * {@code customer_user_id} equals the customer's user id (stamped at checkout
 * while logged in) OR its {@code customer_mobile} equals the customer's saved
 * mobile. This surfaces both account-linked orders and historical guest orders
 * the customer placed with the same number, while never exposing another
 * customer's orders.
 */
@Service
public class AccountOrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final TrackingService trackingService;

    public AccountOrderService(OrderRepository orderRepository,
                               UserRepository userRepository,
                               TrackingService trackingService) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.trackingService = trackingService;
    }

    @Transactional(readOnly = true)
    public List<AccountOrderSummary> history(Long userId) {
        String mobile = mobileOf(userId);
        return orderRepository.findCustomerHistory(userId, mobile).stream()
                .map(AccountOrderSummary::from)
                .toList();
    }

    /**
     * A single order in the customer's history by code, with shipment/tracking
     * fields when a courier record exists. A code that is not the customer's own
     * order yields 404.
     */
    @Transactional(readOnly = true)
    public OrderResponse detail(Long userId, String orderCode) {
        String mobile = mobileOf(userId);
        OrderEntity order = orderRepository.findCustomerOrderByCode(orderCode, userId, mobile)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + orderCode + " does not exist."));
        OrderResponse response = OrderResponse.from(order);
        return trackingService.shipmentFor(order.getId())
                .map(s -> response.withShipment(
                        s.awb(), s.courierName(), s.trackingUrl(), s.estimatedDelivery()))
                .orElse(response);
    }

    private String mobileOf(Long userId) {
        return userRepository.findById(userId).map(User::getMobile).orElse(null);
    }
}
