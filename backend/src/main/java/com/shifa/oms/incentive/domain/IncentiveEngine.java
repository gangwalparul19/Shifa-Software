package com.shifa.oms.incentive.domain;

import com.shifa.oms.incentive.dto.IncentiveTracker.DayCell;
import com.shifa.oms.incentive.dto.IncentiveTracker.DayInfo;
import com.shifa.oms.incentive.dto.IncentiveTracker.DayRef;
import com.shifa.oms.incentive.dto.IncentiveTracker.DayTotal;
import com.shifa.oms.incentive.dto.IncentiveTracker.Month;
import com.shifa.oms.incentive.dto.IncentiveTracker.PersonMonth;
import com.shifa.oms.incentive.dto.IncentiveTracker.Totals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns each salesperson's daily sales into the month tracker: bonus per day, month totals, and the
 * highest and lowest sales days. Pure: it is handed the figures, the rules and "today", so the same
 * input always gives the same result.
 *
 * <p>Definitions, kept in one place:
 * <ul>
 *   <li>The bonus for a day is judged by the rules in force on that day ({@link RuleTimeline}), and
 *       Sundays earn a bonus like any other day.</li>
 *   <li>The <b>highest</b> and <b>lowest</b> day look only at days that are not Sundays, are not
 *       after today, and had at least one order. A day with no sales is not a "lowest sales day":
 *       it is counted separately as a no-sales day, so one day off does not hide the genuinely
 *       weakest selling day. When several days tie, all of them are reported.</li>
 *   <li>A <b>no-sales day</b> is a Monday-to-Saturday day up to today with no order.</li>
 * </ul>
 */
public final class IncentiveEngine {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** A person to track. */
    public record Person(long id, String name, boolean active) {
    }

    /** What a person sold on one day. */
    public record DayFigure(long orders, BigDecimal sales) {
        public static final DayFigure NONE = new DayFigure(0, BigDecimal.ZERO.setScale(2));
    }

    private IncentiveEngine() {
    }

    /**
     * @param figures sales keyed by person id, then by day; a missing entry means no sales
     */
    public static Month build(YearMonth month, LocalDate today, List<Person> people,
                              Map<Long, Map<LocalDate, DayFigure>> figures, RuleTimeline rules) {
        List<LocalDate> dates = new ArrayList<>();
        List<DayInfo> days = new ArrayList<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            dates.add(date);
            days.add(new DayInfo(date, weekday(date), isSunday(date), date.isAfter(today)));
        }

        boolean rulesDefined = false;
        for (LocalDate date : dates) {
            if (!date.isAfter(today) && rules.hasSlabsOn(date)) {
                rulesDefined = true;
                break;
            }
        }

        List<PersonMonth> rows = new ArrayList<>();
        BigDecimal[] teamSales = new BigDecimal[dates.size()];
        BigDecimal[] teamBonus = new BigDecimal[dates.size()];
        long[] teamOrders = new long[dates.size()];
        java.util.Arrays.fill(teamSales, ZERO);
        java.util.Arrays.fill(teamBonus, ZERO);

        for (Person person : people) {
            Map<LocalDate, DayFigure> byDay = figures.getOrDefault(person.id(), Map.of());
            rows.add(personMonth(person, dates, today, byDay, rules, teamSales, teamBonus, teamOrders));
        }
        rows.sort(Comparator.comparing(PersonMonth::sales).reversed()
                .thenComparing(PersonMonth::name, String.CASE_INSENSITIVE_ORDER));

        List<DayTotal> dayTotals = new ArrayList<>();
        long orders = 0;
        BigDecimal sales = ZERO;
        BigDecimal bonus = ZERO;
        int bonusDays = 0;
        for (int i = 0; i < dates.size(); i++) {
            dayTotals.add(new DayTotal(dates.get(i), teamOrders[i], teamSales[i], teamBonus[i]));
            orders += teamOrders[i];
            sales = sales.add(teamSales[i]);
            bonus = bonus.add(teamBonus[i]);
        }
        for (PersonMonth row : rows) {
            bonusDays += row.bonusDays();
        }
        return new Month(days, rows, dayTotals, new Totals(orders, sales, bonus, bonusDays), rulesDefined);
    }

    private static PersonMonth personMonth(Person person, List<LocalDate> dates, LocalDate today,
                                           Map<LocalDate, DayFigure> byDay, RuleTimeline rules,
                                           BigDecimal[] teamSales, BigDecimal[] teamBonus, long[] teamOrders) {
        int n = dates.size();
        long[] orders = new long[n];
        BigDecimal[] sales = new BigDecimal[n];
        BigDecimal[] bonus = new BigDecimal[n];
        BigDecimal[] slab = new BigDecimal[n];

        long totalOrders = 0;
        BigDecimal totalSales = ZERO;
        BigDecimal totalBonus = ZERO;
        BigDecimal sundaySales = ZERO;
        int bonusDays = 0;
        int sellingDays = 0;
        int noSalesDays = 0;

        // Highest / lowest are decided over the "considered" days only.
        BigDecimal max = null;
        BigDecimal min = null;
        for (int i = 0; i < n; i++) {
            LocalDate date = dates.get(i);
            DayFigure figure = byDay.getOrDefault(date, DayFigure.NONE);
            orders[i] = figure.orders();
            sales[i] = figure.sales().setScale(2, RoundingMode.HALF_UP);
            RuleTimeline.Bonus earned = rules.bonusFor(date, sales[i]);
            bonus[i] = earned.amount();
            slab[i] = earned.slab();

            totalOrders += orders[i];
            totalSales = totalSales.add(sales[i]);
            totalBonus = totalBonus.add(bonus[i]);
            teamOrders[i] += orders[i];
            teamSales[i] = teamSales[i].add(sales[i]);
            teamBonus[i] = teamBonus[i].add(bonus[i]);
            if (earned.earned()) {
                bonusDays++;
            }
            if (orders[i] > 0) {
                sellingDays++;
            }
            boolean sunday = isSunday(date);
            if (sunday) {
                sundaySales = sundaySales.add(sales[i]);
            } else if (!date.isAfter(today) && orders[i] == 0) {
                noSalesDays++;
            }
            if (!sunday && !date.isAfter(today) && orders[i] > 0) {
                max = max == null || sales[i].compareTo(max) > 0 ? sales[i] : max;
                min = min == null || sales[i].compareTo(min) < 0 ? sales[i] : min;
            }
        }

        List<DayCell> cells = new ArrayList<>(n);
        List<DayRef> highest = new ArrayList<>();
        List<DayRef> lowest = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDate date = dates.get(i);
            boolean considered = !isSunday(date) && !date.isAfter(today) && orders[i] > 0;
            boolean isHighest = considered && max != null && sales[i].compareTo(max) == 0;
            boolean isLowest = considered && min != null && sales[i].compareTo(min) == 0;
            if (isHighest) {
                highest.add(new DayRef(date, sales[i]));
            }
            if (isLowest) {
                lowest.add(new DayRef(date, sales[i]));
            }
            cells.add(new DayCell(date, orders[i], sales[i], bonus[i], slab[i], isHighest, isLowest));
        }

        BigDecimal average = sellingDays == 0
                ? ZERO
                : totalSales.divide(BigDecimal.valueOf(sellingDays), 2, RoundingMode.HALF_UP);
        return new PersonMonth(person.id(), person.name(), person.active(), List.copyOf(cells), totalOrders,
                totalSales, totalBonus, bonusDays, sellingDays, noSalesDays, sundaySales, average,
                List.copyOf(highest), List.copyOf(lowest));
    }

    private static boolean isSunday(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static String weekday(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
    }
}
