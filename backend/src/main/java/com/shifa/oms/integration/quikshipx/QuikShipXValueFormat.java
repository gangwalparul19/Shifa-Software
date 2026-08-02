package com.shifa.oms.integration.quikshipx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders Shifa values into the shapes QuikShipX expects
 * (contract: {@code docs/QUIKSHIPX-API-V1.md}).
 *
 * <p><b>Every value in the QuikShipX body is a JSON string</b> — amounts, dimensions
 * and quantities included. That is a genuine serialization concern rather than a
 * formatting nicety: emitting {@code "order_amount": 999} instead of
 * {@code "order_amount": "999"} changes the wire type, and emitting
 * {@code 999.00000001} from a double would corrupt the money.
 *
 * <p>Dates are the other trap. QuikShipX documents {@code customer_order_date} as
 * {@code "14 March 2026"} — a human-readable form, not ISO-8601 — so it needs a fixed
 * {@link Locale#ENGLISH} formatter. Leaving the locale to the JVM default would emit
 * a localised month name on a differently-configured server.
 *
 * <p>Pure and total: no Spring, no clock, no I/O.
 */
public final class QuikShipXValueFormat {

    /** {@code "14 March 2026"} — day, full month name, four-digit year. */
    private static final DateTimeFormatter ORDER_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** {@code shipment_pay_mode}: collect on delivery. */
    public static final String PAY_MODE_COD = "1";

    /** {@code shipment_pay_mode}: already paid. */
    public static final String PAY_MODE_PREPAID = "2";

    /** {@code customer_address_type}: residential. */
    public static final String ADDRESS_TYPE_HOME = "1";

    /** {@code customer_address_type}: commercial. */
    public static final String ADDRESS_TYPE_OFFICE = "2";

    private QuikShipXValueFormat() {
        // Pure static helper.
    }

    /**
     * Formats an order date as QuikShipX's documented human-readable form.
     * Null yields an empty string rather than the literal {@code "null"}.
     */
    public static String date(LocalDate date) {
        return date == null ? "" : ORDER_DATE.format(date);
    }

    /** Convenience overload for an order's {@code createdAt}. */
    public static String date(LocalDateTime dateTime) {
        return dateTime == null ? "" : date(dateTime.toLocalDate());
    }

    /**
     * Formats a monetary amount as a plain decimal string with two places.
     *
     * <p>Plain, never scientific: {@link BigDecimal#toString()} would emit
     * {@code 1E+3} for some values, which QuikShipX would not parse as money.
     * Null becomes {@code "0.00"} so a required amount field is never absent.
     */
    public static String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Formats a whole-rupee amount, for fields QuikShipX shows as bare numbers.
     * Still a string on the wire.
     */
    public static String wholeAmount(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /** Formats an integer as a digit string. */
    public static String integer(int value) {
        return Integer.toString(value);
    }

    /** Formats a nullable integer, substituting the given fallback when absent. */
    public static String integer(Integer value, int fallback) {
        return Integer.toString(value == null ? fallback : value);
    }

    /**
     * Never returns null, because a null in the body would serialise as JSON
     * {@code null} where QuikShipX expects a string. Optional fields are sent as
     * {@code ""}, matching the sample request in the contract.
     */
    public static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Composes {@code customer_full_address} from the Shifa address parts.
     *
     * <p>QuikShipX takes a single free-text address plus a separate
     * {@code customer_pincode}, whereas Shifa stores line, city, state and postal code
     * separately. Empty parts are dropped so the result never contains a dangling
     * {@code ", ,"}, and the postal code is deliberately excluded — duplicating it here
     * as well as in {@code customer_pincode} risks the courier reading it twice.
     */
    public static String fullAddress(String addressLine, String city, String state) {
        StringBuilder composed = new StringBuilder();
        appendPart(composed, addressLine);
        appendPart(composed, city);
        appendPart(composed, state);
        return composed.toString();
    }

    private static void appendPart(StringBuilder target, String part) {
        if (part == null) {
            return;
        }
        String trimmed = part.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(", ");
        }
        target.append(trimmed);
    }

    /**
     * The pay mode for an order: COD when anything remains to be collected on
     * delivery, prepaid otherwise (Req 5.13).
     */
    public static String payMode(BigDecimal amountToCollect) {
        boolean collectOnDelivery = amountToCollect != null && amountToCollect.signum() > 0;
        return collectOnDelivery ? PAY_MODE_COD : PAY_MODE_PREPAID;
    }

    /**
     * The {@code cod_amount} to send: the outstanding amount for a COD shipment, and
     * exactly {@code "0"} for a prepaid one. Sending a non-zero COD amount on a prepaid
     * shipment would have the courier collect money twice.
     */
    public static String codAmount(BigDecimal amountToCollect) {
        if (amountToCollect == null || amountToCollect.signum() <= 0) {
            return "0";
        }
        return wholeAmount(amountToCollect);
    }
}
