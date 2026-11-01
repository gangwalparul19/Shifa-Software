package com.shifa.oms.incentive.dto;

import com.shifa.oms.incentive.dto.IncentiveRules.RuleVersionView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The month tracker: each salesperson's sales and daily bonus for every day of a month. */
public final class IncentiveTracker {

    private IncentiveTracker() {
    }

    /**
     * @param month        {@code yyyy-MM}
     * @param rules        the rule versions that applied on at least one day of the month
     * @param rulesDefined whether any slab was in force on any day of the month up to today
     */
    public record Response(String month, LocalDate monthStart, LocalDate monthEnd, LocalDate today,
                           List<DayInfo> days, List<PersonMonth> people, List<DayTotal> dayTotals,
                           Totals totals, List<RuleVersionView> rules, boolean rulesDefined) {
    }

    /** A calendar day of the month. {@code weekday} is MON..SUN. */
    public record DayInfo(LocalDate date, String weekday, boolean sunday, boolean future) {
    }

    /**
     * One person on one day.
     *
     * @param slab    the minimum sales of the slab that earned the bonus (null when none)
     * @param highest this is (one of) the person's highest non-Sunday day(s) this month
     * @param lowest  this is (one of) the person's lowest non-Sunday selling day(s) this month
     */
    public record DayCell(LocalDate date, long orders, BigDecimal sales, BigDecimal bonus, BigDecimal slab,
                          boolean highest, boolean lowest) {
    }

    /** A day and its sales, used to name the highest and lowest days. */
    public record DayRef(LocalDate date, BigDecimal sales) {
    }

    /**
     * One person's month.
     *
     * @param sellingDays          days (any weekday) with at least one order
     * @param noSalesDays          Monday to Saturday days up to today with no order
     * @param sundaySales          sales made on Sundays (counted in totals and bonus, but never
     *                             considered for the highest and lowest day)
     * @param averagePerSellingDay total sales divided by selling days
     * @param highest              the highest non-Sunday day; several when tied; empty without sales
     * @param lowest               the lowest non-Sunday selling day; several when tied
     */
    public record PersonMonth(long personId, String name, boolean active, List<DayCell> days, long orders,
                              BigDecimal sales, BigDecimal bonus, int bonusDays, int sellingDays,
                              int noSalesDays, BigDecimal sundaySales, BigDecimal averagePerSellingDay,
                              List<DayRef> highest, List<DayRef> lowest) {
    }

    /** The whole team on one day. */
    public record DayTotal(LocalDate date, long orders, BigDecimal sales, BigDecimal bonus) {
    }

    /** The whole team for the month; {@code bonusDays} counts person-days that earned a bonus. */
    public record Totals(long orders, BigDecimal sales, BigDecimal bonus, int bonusDays) {
    }

    /** The engine's result before the service adds the request-level fields. */
    public record Month(List<DayInfo> days, List<PersonMonth> people, List<DayTotal> dayTotals,
                        Totals totals, boolean rulesDefined) {
    }
}
