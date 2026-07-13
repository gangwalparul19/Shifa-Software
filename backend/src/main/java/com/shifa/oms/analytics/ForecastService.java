package com.shifa.oms.analytics;

import com.shifa.oms.analytics.dto.ForecastReport;
import com.shifa.oms.analytics.dto.ForecastReport.CashForecast;
import com.shifa.oms.analytics.dto.ForecastReport.ProductForecastRow;
import com.shifa.oms.order.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Demand & cash forecasting (FEATURE-ROADMAP §6.5): a simple, explainable
 * moving-average projection — per-product demand and expected COD collections —
 * over existing order history. Deliberately distinct from the LOW_STOCK_REORDER
 * insight (which raises reorder alerts): this projects units and cash forward.
 */
@Service
public class ForecastService {

    private static final int DEFAULT_LOOKBACK_DAYS = 30;
    private static final int DEFAULT_HORIZON_DAYS = 30;
    private static final int MAX_DAYS = 180;
    private static final int TOP_PRODUCTS = 15;
    /** Recent window (days) used for the COD collection run-rate. */
    private static final int CASH_WINDOW_DAYS = 28;

    private final OrderRepository orderRepository;
    private final Clock clock;

    @Autowired
    public ForecastService(OrderRepository orderRepository) {
        this(orderRepository, Clock.systemDefaultZone());
    }

    ForecastService(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    /**
     * The demand + cash forecast.
     *
     * @param lookbackDays trailing window for the run-rate (clamped 1..180, default 30)
     * @param horizonDays  forward projection horizon (clamped 1..180, default 30)
     */
    @Transactional(readOnly = true)
    public ForecastReport report(Integer lookbackDays, Integer horizonDays) {
        int lookback = clamp(lookbackDays, DEFAULT_LOOKBACK_DAYS);
        int horizon = clamp(horizonDays, DEFAULT_HORIZON_DAYS);
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = today.minusDays(lookback).atStartOfDay();

        List<ProductForecastRow> demand = new ArrayList<>();
        for (OrderRepository.ProductDemandRow r : orderRepository.productDemandBetween(from, now)) {
            if (r.getProductId() == null) {
                continue;
            }
            double avgPerDay = lookback == 0 ? 0.0 : (double) r.getUnits() / lookback;
            long projected = Math.round(avgPerDay * horizon);
            demand.add(new ProductForecastRow(
                    r.getProductId(),
                    r.getProductName() == null ? "Product #" + r.getProductId() : r.getProductName(),
                    r.getUnits(),
                    round2(avgPerDay),
                    projected));
        }
        demand.sort(Comparator.comparingLong(ProductForecastRow::projectedUnits).reversed());
        if (demand.size() > TOP_PRODUCTS) {
            demand = new ArrayList<>(demand.subList(0, TOP_PRODUCTS));
        }

        return new ForecastReport(lookback, horizon, demand, cashForecast(now));
    }

    private CashForecast cashForecast(LocalDateTime now) {
        BigDecimal outstanding = nz(orderRepository.sumOutstandingCodActive());
        BigDecimal collected = nz(orderRepository.sumCodCollectedSince(now.minusDays(CASH_WINDOW_DAYS)));
        BigDecimal weeks = BigDecimal.valueOf(CASH_WINDOW_DAYS / 7.0);
        BigDecimal avgWeekly = weeks.signum() == 0 ? BigDecimal.ZERO
                : collected.divide(weeks, 2, RoundingMode.HALF_UP);
        Double weeksToClear = avgWeekly.signum() <= 0 ? null
                : outstanding.divide(avgWeekly, 1, RoundingMode.HALF_UP).doubleValue();
        return new CashForecast(
                outstanding.setScale(2, RoundingMode.HALF_UP), CASH_WINDOW_DAYS,
                collected.setScale(2, RoundingMode.HALF_UP), avgWeekly, weeksToClear);
    }

    private static int clamp(Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(1, Math.min(MAX_DAYS, value));
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
