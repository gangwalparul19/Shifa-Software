package com.shifa.oms.chatbot.domain;

import com.shifa.oms.dashboard.DashboardChannel;
import com.shifa.oms.order.OrderStatusGroup;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What the parser understood from a question. Everything the answer depends on
 * is explicit here, so the service never has to guess and a wrong reading is
 * visible in the answer text.
 *
 * @param kind             QUERY when a data question was recognised, otherwise HELP / UNKNOWN
 * @param metric           the figure asked for (sales questions)
 * @param period           the first resolved date window ({@link Period#allTime()} when none was given)
 * @param periodDefaulted  true when the question named no period
 * @param channel          single-channel filter ({@code ALL} for none)
 * @param compareChannels  channels to put side by side (set when the question names several)
 * @param people           resolved staff filter (several means "compare these people")
 * @param teamScope        true when the question asked for a team lead's whole team
 * @param ambiguousPeople  staff the typed name could equally refer to (the bot asks which)
 * @param products         resolved product filter (empty means all products)
 * @param breakdown        the dimension to rank by, or {@link Breakdown#NONE} for a single figure
 * @param topN             how many rows to show in a breakdown
 * @param ascending        true for "lowest / least / worst" rankings
 * @param unresolved       names that followed "by / for / of ..." but matched nothing (the bot refuses to guess)
 * @param ignored          unrecognised words that were left out of the answer
 * @param topics           what the question is about (several for "received in bank and COD to collect")
 * @param extraPeriods     further periods named in the same question ("today, yesterday, this week")
 * @param accounts         resolved bank / QR accounts
 * @param ambiguousAccounts accounts a typed name could equally refer to
 * @param unresolvedAccounts "bank 3" style references that matched no account
 * @param statusGroup      restricts to orders currently in one lifecycle stage, or {@code null}
 * @param orderCode        an order number found in the question (upper case), or {@code null}
 * @param referenceNumber  a tracking / courier / QuikShip number found in the question, or {@code null}
 * @param customerMobile   a customer mobile number found in the question, or {@code null}
 * @param average          true when the question asked for an average (order value)
 */
public record ParsedQuestion(
        Kind kind,
        Metric metric,
        Period period,
        boolean periodDefaulted,
        DashboardChannel channel,
        List<DashboardChannel> compareChannels,
        List<PersonRef> people,
        boolean teamScope,
        List<PersonRef> ambiguousPeople,
        List<ProductRef> products,
        Breakdown breakdown,
        int topN,
        boolean ascending,
        List<String> unresolved,
        List<String> ignored,
        Set<Topic> topics,
        List<Period> extraPeriods,
        List<AccountRef> accounts,
        List<AccountRef> ambiguousAccounts,
        List<String> unresolvedAccounts,
        OrderStatusGroup statusGroup,
        String orderCode,
        String referenceNumber,
        String customerMobile,
        boolean average) {

    public enum Kind { QUERY, HELP, UNKNOWN }

    public enum Metric { ORDERS, SALES, UNITS, SUMMARY }

    public enum Breakdown { NONE, PERSON, PRODUCT, CHANNEL, STATUS }

    /** The subject of a question. SALES is the default; the others are mutually combinable only where noted. */
    public enum Topic {
        /** Orders, sales and units (the original questions). */
        SALES,
        /** Money received when orders were punched, per bank / QR account. Combinable with COD. */
        RECEIPTS,
        /** Cash on delivery payable on orders placed in a period. Combinable with RECEIPTS. */
        COD,
        /** Unsettled COD the courier still owes us (all-time backlog). */
        COD_COURIER,
        /** "What needs attention": the owner's backlog numbers. */
        ATTENTION,
        /** Status of one order, found by order number / tracking number. */
        ORDER_LOOKUP,
        /** Recent orders of one customer, found by mobile number. */
        CUSTOMER_LOOKUP
    }

    public static ParsedQuestion of(Kind kind, Period period) {
        return new ParsedQuestion(kind, Metric.SUMMARY, period, true, DashboardChannel.ALL, List.of(), List.of(),
                false, List.of(), List.of(), Breakdown.NONE, 10, false, List.of(), List.of(),
                EnumSet.of(Topic.SALES), List.of(), List.of(), List.of(), List.of(), null, null, null, null, false);
    }

    public boolean has(Topic topic) {
        return topics.contains(topic);
    }

    /** Every period named in the question, in the order given (never empty). */
    public List<Period> allPeriods() {
        List<Period> all = new ArrayList<>();
        all.add(period);
        all.addAll(extraPeriods);
        return all;
    }
}
