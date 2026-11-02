package com.shifa.oms.mail.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the morning email says about one business day (normally yesterday). Plain data: the
 * email renderer is a pure function of it, and {@link MorningBriefService} fills it from the database.
 *
 * <p>Every optional section ({@code banks}, {@code cod}, {@code topSalespeople}, {@code incentive},
 * {@code highlights}, {@code dues}, {@code attention}) is {@code null} when it could not be loaded, so
 * one failing query never stops the email; the renderer then shows a short "could not be loaded"
 * note in its place. An empty list means "loaded, and there was nothing".
 *
 * @param day            the business day summarised (Asia/Kolkata)
 * @param yesterday      whether {@code day} is the day before the report was built; the wording says
 *                       "yesterday" when it is and "on Fri 31 Oct" otherwise (a manual run for another date)
 * @param headline       the day's totals and how they compare with other days
 * @param channels       Portal, Shopify and Store (POS), always in that order
 * @param total          all three channels together
 * @param banks          money captured that day, per bank / QR account
 * @param cod            cash on delivery due and received
 * @param topSalespeople up to three best salespeople of the day, best first
 * @param incentive      the daily incentive program and who earned it
 * @param highlights     customers, best sellers, deliveries and lost orders
 * @param dues           money still to collect, across all dates, as of this morning
 * @param attention      work waiting for an admin this morning
 */
public record MorningBrief(
        LocalDate day,
        boolean yesterday,
        Headline headline,
        List<ChannelRow> channels,
        ChannelRow total,
        Banks banks,
        CashOnDelivery cod,
        List<PersonRow> topSalespeople,
        Incentive incentive,
        Highlights highlights,
        Dues dues,
        Attention attention) {

    /** A comparison day's totals. */
    public record Comparison(LocalDate day, long orders, BigDecimal sales) {
    }

    /**
     * @param orders             orders placed, rejected / payment-rejected / cancelled left out
     * @param sales              their total value
     * @param averageOrderValue  sales divided by orders (zero without orders)
     * @param previousDay        the day before
     * @param sameWeekdayLastWeek the same weekday a week earlier
     * @param monthStart         first day of the month the day belongs to
     * @param monthOrders        orders from {@code monthStart} to the day, inclusive
     * @param monthSales         their total value
     */
    public record Headline(long orders, BigDecimal sales, BigDecimal averageOrderValue,
                           Comparison previousDay, Comparison sameWeekdayLastWeek,
                           LocalDate monthStart, long monthOrders, BigDecimal monthSales) {
    }

    /**
     * One sales source.
     *
     * @param label        for example "Portal"
     * @param orders       orders placed
     * @param sales        their total value
     * @param codToCollect cash still to be collected on delivery on those orders
     */
    public record ChannelRow(String label, long orders, BigDecimal sales, BigDecimal codToCollect) {
    }

    /** One receiving account, or the "no account recorded" bucket. */
    public record BankRow(String account, long payments, BigDecimal amount) {
    }

    /**
     * @param rows                accounts with money, largest first; "no account" last
     * @param total               everything captured that day
     * @param payments            number of payments
     * @param awaitingVerification part of the total the payment verifier has not checked yet
     * @param rejected            part of the total the payment verifier rejected
     */
    public record Banks(List<BankRow> rows, BigDecimal total, long payments,
                        BigDecimal awaitingVerification, BigDecimal rejected) {
    }

    /**
     * @param toCollect        cash on delivery due on the day's orders
     * @param ordersWithCod    how many of those orders carry cash on delivery
     * @param receivedThatDay  cash on delivery settled that day (courier payouts, in-house collections)
     * @param receivedCount    how many orders that covers
     */
    public record CashOnDelivery(BigDecimal toCollect, long ordersWithCod,
                                 BigDecimal receivedThatDay, long receivedCount) {
    }

    /** A salesperson's day. {@code bonus} is zero when nothing was earned. */
    public record PersonRow(int rank, String name, long orders, BigDecimal sales, BigDecimal bonus) {
    }

    /** A daily incentive slab: reaching {@code minSales} in a day earns {@code bonus}. */
    public record Slab(BigDecimal minSales, BigDecimal bonus) {
    }

    /** Someone who earned the daily bonus. {@code slab} is the minimum of the slab they reached. */
    public record Earner(String name, long orders, BigDecimal sales, BigDecimal slab, BigDecimal bonus) {
    }

    /**
     * @param active       whether any slab was in force that day
     * @param slabs        the slabs in force, lowest first (empty when not active)
     * @param salespeople  active salespeople who could have earned it
     * @param earners      those who did, biggest bonus first
     * @param totalBonus   everything earned that day
     * @param monthBonus   earned from the first of the month up to and including the day
     * @param monthBonusDays person-days that earned a bonus in that stretch
     */
    public record Incentive(boolean active, List<Slab> slabs, int salespeople, List<Earner> earners,
                            BigDecimal totalBonus, BigDecimal monthBonus, int monthBonusDays) {
    }

    /** A best-selling product. */
    public record ProductRow(String name, long units, BigDecimal sales) {
    }

    /**
     * @param newCustomers       customers whose first order was that day
     * @param returningCustomers customers who had ordered before
     * @param bestSellers        up to three products by sales
     * @param deliveredOrders    orders delivered that day
     * @param deliveredValue     their value
     * @param failedOrders       orders that failed delivery or came back that day
     * @param failedValue        their value
     * @param lostOrders         orders placed that day that were rejected or cancelled
     * @param lostValue          their value
     */
    public record Highlights(long newCustomers, long returningCustomers, List<ProductRow> bestSellers,
                             long deliveredOrders, BigDecimal deliveredValue,
                             long failedOrders, BigDecimal failedValue,
                             long lostOrders, BigDecimal lostValue) {
    }

    /**
     * Money still to collect.
     *
     * @param customersOwe     balances customers still owe on active orders
     * @param courierOwes      cash on delivery the courier has not paid out yet
     * @param overSlaOrders    of that, orders older than the payout deadline
     * @param overSlaAmount    their value
     * @param slaDays          the payout deadline in days
     * @param pendingClaims    open courier loss claims
     * @param claimsAmount     their value
     * @param total            everything above
     */
    public record Dues(BigDecimal customersOwe, BigDecimal courierOwes, long overSlaOrders,
                       BigDecimal overSlaAmount, int slaDays, long pendingClaims,
                       BigDecimal claimsAmount, BigDecimal total) {
    }

    /** Work waiting this morning. */
    public record Attention(long approvalsWaiting, long paymentsToVerify, long failedDeliveries,
                            long stuckShipments) {

        public boolean isEmpty() {
            return approvalsWaiting == 0 && paymentsToVerify == 0 && failedDeliveries == 0 && stuckShipments == 0;
        }
    }

    /**
     * How much {@code now} is above ({@code +}) or below ({@code -}) {@code before}, in percent with
     * one decimal. {@code null} when there is nothing to compare with (before is zero or missing).
     */
    public static BigDecimal changePercent(BigDecimal now, BigDecimal before) {
        if (now == null || before == null || before.signum() == 0) {
            return null;
        }
        return now.subtract(before).multiply(BigDecimal.valueOf(100))
                .divide(before, 1, RoundingMode.HALF_UP);
    }
}
