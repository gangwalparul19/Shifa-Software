package com.shifa.oms.integration.quikshipx;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature: shopify-quikshipx-order-sync, Property 36 (partial): QuikShipX values are
 * string-typed and internally consistent, plus the order-reference round trip.
 *
 * <p>Covers the two pure helpers that decide what actually goes on the wire:
 * {@link QuikShipXValueFormat} and {@link OrderReference}. The whole-body property lives
 * in {@code QuikShipXSubmissionCodecPropertyTest}.
 *
 * <p>Validates: Requirements 5.3, 5.13, 8.11
 */
class QuikShipXValueFormatTest {

    // --- Dates -------------------------------------------------------------

    @Test
    void dateUsesQuikShipXsHumanReadableForm() {
        // The contract's own example. NOT ISO-8601.
        assertThat(QuikShipXValueFormat.date(LocalDate.of(2026, 3, 14))).isEqualTo("14 March 2026");
        // No zero padding on the day.
        assertThat(QuikShipXValueFormat.date(LocalDate.of(2026, 8, 1))).isEqualTo("1 August 2026");
        assertThat(QuikShipXValueFormat.date((LocalDate) null)).isEmpty();
    }

    @Test
    void dateIgnoresTheJvmDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            // A server configured for a non-English locale must still send English
            // month names, or QuikShipX cannot parse the date.
            Locale.setDefault(Locale.forLanguageTag("hi-IN"));
            assertThat(QuikShipXValueFormat.date(LocalDate.of(2026, 3, 14))).isEqualTo("14 March 2026");
        } finally {
            Locale.setDefault(original);
        }
    }

    // --- Money -------------------------------------------------------------

    @Property(tries = 500)
    void moneyIsAlwaysPlainWithTwoDecimals(@ForAll @LongRange(min = 0, max = 99_999_999) long paise) {
        BigDecimal amount = BigDecimal.valueOf(paise, 2);

        String formatted = QuikShipXValueFormat.money(amount);

        // Never scientific notation: BigDecimal.toString() would emit 1E+3 for some
        // values and QuikShipX would not read that as money.
        assertThat(formatted).doesNotContain("E").doesNotContain("e");
        assertThat(formatted).matches("-?\\d+\\.\\d{2}");
        assertThat(new BigDecimal(formatted)).isEqualByComparingTo(amount);
    }

    @Test
    void moneyHandlesLargeValuesAndNullsWithoutScientificNotation() {
        assertThat(QuikShipXValueFormat.money(new BigDecimal("1E+3"))).isEqualTo("1000.00");
        assertThat(QuikShipXValueFormat.money(null)).isEqualTo("0.00");
    }

    // --- Pay mode and COD --------------------------------------------------

    @Property(tries = 500)
    void payModeAndCodAmountAgreeWithEachOther(
            @ForAll @LongRange(min = -5_000, max = 500_000) long rupees) {

        BigDecimal toCollect = BigDecimal.valueOf(rupees);

        String payMode = QuikShipXValueFormat.payMode(toCollect);
        String codAmount = QuikShipXValueFormat.codAmount(toCollect);

        if (rupees > 0) {
            assertThat(payMode).isEqualTo(QuikShipXValueFormat.PAY_MODE_COD);
            assertThat(new BigDecimal(codAmount)).isEqualByComparingTo(toCollect);
        } else {
            assertThat(payMode).isEqualTo(QuikShipXValueFormat.PAY_MODE_PREPAID);
            // A non-zero COD on a prepaid shipment would have the courier collect
            // money the customer has already paid.
            assertThat(codAmount).isEqualTo("0");
        }
    }

    @Test
    void nullAmountToCollectIsPrepaid() {
        assertThat(QuikShipXValueFormat.payMode(null)).isEqualTo(QuikShipXValueFormat.PAY_MODE_PREPAID);
        assertThat(QuikShipXValueFormat.codAmount(null)).isEqualTo("0");
    }

    // --- Address composition ----------------------------------------------

    @Test
    void fullAddressJoinsPresentPartsAndDropsBlanks() {
        assertThat(QuikShipXValueFormat.fullAddress("12 MG Road", "Pune", "Maharashtra"))
                .isEqualTo("12 MG Road, Pune, Maharashtra");
        // A blank middle part must not leave a dangling separator.
        assertThat(QuikShipXValueFormat.fullAddress("12 MG Road", "  ", "Maharashtra"))
                .isEqualTo("12 MG Road, Maharashtra");
        assertThat(QuikShipXValueFormat.fullAddress(null, null, null)).isEmpty();
        assertThat(QuikShipXValueFormat.fullAddress("  12 MG Road  ", "Pune", null))
                .isEqualTo("12 MG Road, Pune");
    }

    @Property(tries = 300)
    void fullAddressNeverContainsTheDanglingSeparator(
            @ForAll @IntRange(min = 0, max = 7) int mask) {

        String line = (mask & 1) != 0 ? "12 MG Road" : "";
        String city = (mask & 2) != 0 ? "Pune" : "";
        String state = (mask & 4) != 0 ? "Maharashtra" : "";

        String composed = QuikShipXValueFormat.fullAddress(line, city, state);

        assertThat(composed).doesNotContain(", ,");
        assertThat(composed).doesNotStartWith(",");
        assertThat(composed).doesNotEndWith(",");
    }

    @Test
    void textNeverReturnsNull() {
        // A null would serialise as JSON null where QuikShipX expects a string.
        assertThat(QuikShipXValueFormat.text(null)).isEmpty();
        assertThat(QuikShipXValueFormat.text("  padded  ")).isEqualTo("padded");
    }

    // --- Order reference ---------------------------------------------------

    @Property(tries = 500)
    void theOrderReferenceRoundTripsBackToTheOrderCode(
            @ForAll @IntRange(min = 1, max = 99_999) int suffix) {

        String orderCode = "SHR-" + suffix;
        String prefix = "SHIFA-";

        String reference = OrderReference.of(prefix, orderCode);

        // The channel is visible in the QuikShipX portal...
        assertThat(reference).isEqualTo("SHIFA-" + orderCode);
        // ...and the reference is still resolvable back to the Shifa order, which is
        // what makes it usable as the correlation key.
        assertThat(OrderReference.orderCodeFrom(prefix, reference)).contains(orderCode);
        assertThat(OrderReference.matchesPrefix(prefix, reference)).isTrue();
    }

    @Property(tries = 300)
    void theReferenceIsStableAcrossRetries(@ForAll @IntRange(min = 1, max = 99_999) int suffix) {
        String orderCode = "SHR-" + suffix;

        // Determinism is what makes it a safe idempotency reference: a retry after an
        // ambiguous timeout must not look like a new order to QuikShipX.
        assertThat(OrderReference.of("SHIFA-", orderCode))
                .isEqualTo(OrderReference.of("SHIFA-", orderCode));
    }

    @Test
    void aReferenceWithoutTheExpectedPrefixDoesNotResolve() {
        // Mis-resolving would corrupt another order's status history, so an
        // unrecognised reference yields empty rather than a guess.
        assertThat(OrderReference.orderCodeFrom("SHIFA-", "SHOPIFY-1234")).isEmpty();
        assertThat(OrderReference.orderCodeFrom("SHIFA-", "SHIFA-")).isEmpty();
        assertThat(OrderReference.orderCodeFrom("SHIFA-", null)).isEmpty();
        assertThat(OrderReference.orderCodeFrom("SHIFA-", "   ")).isEmpty();
        // Prefix matching is case-insensitive: portals often upper-case identifiers.
        assertThat(OrderReference.orderCodeFrom("SHIFA-", "shifa-SHR-1")).contains("SHR-1");
    }

    @Test
    void anEmptyPrefixPassesTheReferenceThrough() {
        assertThat(OrderReference.of(null, "SHR-1")).isEqualTo("SHR-1");
        assertThat(OrderReference.orderCodeFrom(null, "SHR-1")).contains("SHR-1");
    }

    @Test
    void aBlankOrderCodeIsRejectedRatherThanSilentlyBreakingCorrelation() {
        assertThatThrownBy(() -> OrderReference.of("SHIFA-", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderCode");
        assertThatThrownBy(() -> OrderReference.of("SHIFA-", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void integerFormattingSubstitutesTheFallbackWhenAbsent() {
        assertThat(QuikShipXValueFormat.integer(7)).isEqualTo("7");
        assertThat(QuikShipXValueFormat.integer(Optional.of(3).get(), 500)).isEqualTo("3");
        assertThat(QuikShipXValueFormat.integer(null, 500)).isEqualTo("500");
    }
}
