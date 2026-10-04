package com.shifa.oms.analytics;

import com.shifa.oms.analytics.dto.DeliveryPerformanceReport;
import com.shifa.oms.order.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Delivery-performance analytics (ENHANCEMENT 3.3): delivered-vs-failed rates
 * sliced by courier, destination state, and pincode band. Read-only SQL
 * aggregation over orders that reached a terminal delivery outcome
 * (delivered ∪ failed) — no full-table scan, no new tables.
 *
 * <p>Each slice is sorted worst-success-rate-first (then by volume) so the most
 * problematic couriers / regions surface at the top; the owner can switch
 * high-RTO pincodes to prepaid-only or move volume off a weak courier.
 */
@Service
public class DeliveryPerformanceService {

    private final OrderRepository orderRepository;

    public DeliveryPerformanceService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /** Builds the full delivery-performance report (all three slices + overall). */
    @Transactional(readOnly = true)
    public DeliveryPerformanceReport report() {
        List<DeliveryPerformanceReport.Row> byCourier = rows(orderRepository.deliveryOutcomeByCourier());
        List<DeliveryPerformanceReport.Row> byState = rows(orderRepository.deliveryOutcomeByState());
        List<DeliveryPerformanceReport.Row> byPincode = rows(orderRepository.deliveryOutcomeByPincodeBand());
        return new DeliveryPerformanceReport(overall(byState), byCourier, byState, byPincode);
    }

    /** Maps aggregate rows → report rows (with total + success rate), worst-first. */
    private static List<DeliveryPerformanceReport.Row> rows(List<OrderRepository.DeliveryOutcomeRow> raw) {
        List<DeliveryPerformanceReport.Row> result = new ArrayList<>(raw.size());
        for (OrderRepository.DeliveryOutcomeRow r : raw) {
            long delivered = r.getDelivered();
            long failed = r.getFailed();
            long total = delivered + failed;
            if (total == 0) {
                continue;
            }
            result.add(new DeliveryPerformanceReport.Row(
                    r.getDimension(), delivered, failed, total, successRate(delivered, total)));
        }
        // Worst success rate first; ties broken by larger volume (more at stake).
        result.sort(Comparator
                .comparingDouble(DeliveryPerformanceReport.Row::successRate)
                .thenComparing(Comparator.comparingLong(DeliveryPerformanceReport.Row::total).reversed()));
        return result;
    }

    /** The business-wide overall row, summed from any complete slice (state here). */
    private static DeliveryPerformanceReport.Row overall(List<DeliveryPerformanceReport.Row> slice) {
        long delivered = 0;
        long failed = 0;
        for (DeliveryPerformanceReport.Row r : slice) {
            delivered += r.delivered();
            failed += r.failed();
        }
        long total = delivered + failed;
        return new DeliveryPerformanceReport.Row("Overall", delivered, failed, total,
                successRate(delivered, total));
    }

    private static double successRate(long delivered, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(delivered * 100.0 / total)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
