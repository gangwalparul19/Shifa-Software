package com.shifa.oms.finance;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.finance.dto.ProfitLossResponse;
import com.shifa.oms.finance.dto.ProfitLossResponse.ExpenseCategoryAmount;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Profit &amp; loss reporting service (Feature C3).
 *
 * <p>Computes a P&amp;L summary for a date window on the fly (stores nothing) by
 * combining three existing data sources:
 * <ul>
 *   <li><strong>revenue</strong> — sum of {@code orders.total_amount} for orders
 *       created in the window, excluding {@link OrderStatus#REJECTED} and
 *       {@link OrderStatus#CANCELLED} (they produced no sellable revenue);</li>
 *   <li><strong>courier/claim costs</strong> — derived from the reconciliation
 *       {@link ReceivableEntity} rows created in the window. The schema does not
 *       model per-shipment courier charges, so {@code courierCost} is defined as
 *       the value of loss/damage claims not yet recovered from the courier
 *       ({@code claimsOutstanding}); {@code claimsRecovered} and
 *       {@code codOutstanding} are also surfaced (see {@link ProfitLossResponse});</li>
 *   <li><strong>expenses</strong> — sum of {@code expenses.amount} incurred in
 *       the window, with a per-category breakdown.</li>
 * </ul>
 *
 * <p>{@code netProfit = revenue − courierCost − totalExpenses}.
 */
@Service
public class ProfitLossService {

    /** Order statuses that never count towards revenue. */
    private static final Set<OrderStatus> NON_REVENUE_STATUSES =
            EnumSet.of(OrderStatus.REJECTED, OrderStatus.CANCELLED);

    private final OrderRepository orderRepository;
    private final ReceivableRepository receivableRepository;
    private final ExpenseRepository expenseRepository;

    public ProfitLossService(OrderRepository orderRepository,
                             ReceivableRepository receivableRepository,
                             ExpenseRepository expenseRepository) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.expenseRepository = expenseRepository;
    }

    /**
     * Builds the P&amp;L summary for the inclusive {@code [from, to]} window.
     *
     * @param from inclusive lower-bound date (required)
     * @param to   inclusive upper-bound date (required, not before {@code from})
     */
    @Transactional(readOnly = true)
    public ProfitLossResponse report(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ValidationException("Both 'from' and 'to' dates are required.");
        }
        if (to.isBefore(from)) {
            throw new ValidationException("'to' date must not be before 'from' date.");
        }
        LocalDateTime fromTs = from.atStartOfDay();
        LocalDateTime toTs = to.atTime(LocalTime.MAX);

        BigDecimal revenue = revenueInWindow(fromTs, toTs);

        BigDecimal claimsOutstanding = BigDecimal.ZERO;
        BigDecimal claimsRecovered = BigDecimal.ZERO;
        BigDecimal codOutstanding = BigDecimal.ZERO;
        for (ReceivableEntity r : receivableRepository.findByCreatedAtBetween(fromTs, toTs)) {
            BigDecimal amount = r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO;
            if (r.getType() == ReceivableType.CLAIM_RECEIVABLE) {
                if (r.isSettled()) {
                    claimsRecovered = claimsRecovered.add(amount);
                } else {
                    claimsOutstanding = claimsOutstanding.add(amount);
                }
            } else if (r.getType() == ReceivableType.COD_RECEIVABLE && !r.isSettled()) {
                codOutstanding = codOutstanding.add(amount);
            }
        }
        // Documented proxy: the only courier-attributable cost in the schema is
        // the value of loss/damage claims not yet recovered from the courier.
        BigDecimal courierCost = claimsOutstanding;

        List<ExpenseCategoryAmount> expenseByCategory = new ArrayList<>();
        BigDecimal totalExpenses = expensesInWindow(from, to, expenseByCategory);

        BigDecimal netProfit = revenue.subtract(courierCost).subtract(totalExpenses);

        return new ProfitLossResponse(
                from, to,
                scale(revenue),
                scale(courierCost),
                scale(claimsOutstanding),
                scale(claimsRecovered),
                scale(codOutstanding),
                scale(totalExpenses),
                expenseByCategory,
                scale(netProfit));
    }

    private BigDecimal revenueInWindow(LocalDateTime fromTs, LocalDateTime toTs) {
        BigDecimal revenue = BigDecimal.ZERO;
        for (OrderEntity o : orderRepository.findByCreatedAtBetween(fromTs, toTs)) {
            if (o.getOrderStatus() != null && NON_REVENUE_STATUSES.contains(o.getOrderStatus())) {
                continue;
            }
            if (o.getTotalAmount() != null) {
                revenue = revenue.add(o.getTotalAmount());
            }
        }
        return revenue;
    }

    /**
     * Sums expenses in the window and fills {@code breakdown} with the per-category
     * totals (in first-seen order). Returns the grand total.
     */
    private BigDecimal expensesInWindow(LocalDate from, LocalDate to,
                                        List<ExpenseCategoryAmount> breakdown) {
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Expense e : expenseRepository.findByIncurredOnBetween(from, to)) {
            BigDecimal amount = e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO;
            total = total.add(amount);
            byCategory.merge(e.getCategory(), amount, BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : byCategory.entrySet()) {
            breakdown.add(new ExpenseCategoryAmount(entry.getKey(), scale(entry.getValue())));
        }
        return total;
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
