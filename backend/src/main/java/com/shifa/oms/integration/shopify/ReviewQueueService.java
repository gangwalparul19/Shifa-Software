package com.shifa.oms.integration.shopify;

import com.shifa.oms.integration.shopify.dto.ReviewQueueRow;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side of the Shopify review queue (Req 3.9).
 *
 * <p>The queue is derived, not stored: it is exactly the set of orders carrying at least
 * one {@link OrderReviewReason}. There is no "resolved" flag, because the reason rows
 * themselves are the record — an admin fixes the underlying problem (maps the SKU, adds the
 * phone number) and the reason is removed, which is a smaller and less lie-prone model than
 * a separate resolution state that can disagree with the order.
 *
 * <p>Loading is two queries plus a batch fetch, never per-row: the distinct order ids, the
 * orders, then every reason for those ids in one go.
 */
@Service
public class ReviewQueueService {

    /** Safety cap. A queue longer than this means something systemic is wrong, not busy. */
    private static final int MAX_ROWS = 200;

    private final OrderReviewReasonRepository reviewReasonRepository;
    private final OrderRepository orderRepository;

    public ReviewQueueService(OrderReviewReasonRepository reviewReasonRepository,
                              OrderRepository orderRepository) {
        this.reviewReasonRepository = reviewReasonRepository;
        this.orderRepository = orderRepository;
    }

    /** Every order needing review, newest first, capped at {@value #MAX_ROWS}. */
    @Transactional(readOnly = true)
    public List<ReviewQueueRow> list() {
        List<Long> orderIds = reviewReasonRepository.findDistinctOrderIds();
        if (orderIds.isEmpty()) {
            return List.of();
        }
        if (orderIds.size() > MAX_ROWS) {
            // findDistinctOrderIds is already ordered by order id descending, which is
            // newest-first, so truncating keeps the most recent.
            orderIds = orderIds.subList(0, MAX_ROWS);
        }

        Map<Long, List<OrderReviewReason>> reasonsByOrder = new LinkedHashMap<>();
        for (OrderReviewReason reason : reviewReasonRepository
                .findByOrderIdInOrderByOrderIdAscIdAsc(orderIds)) {
            reasonsByOrder.computeIfAbsent(reason.getOrderId(), id -> new ArrayList<>()).add(reason);
        }

        List<OrderEntity> orders = orderRepository.findAllById(orderIds);
        return orders.stream()
                .sorted(Comparator.comparing(OrderEntity::getId).reversed())
                .map(order -> ReviewQueueRow.from(
                        order, reasonsByOrder.getOrDefault(order.getId(), List.of())))
                .toList();
    }

    /** The queue length, for the admin dashboard tile. */
    @Transactional(readOnly = true)
    public long count() {
        return reviewReasonRepository.findDistinctOrderIds().size();
    }
}
