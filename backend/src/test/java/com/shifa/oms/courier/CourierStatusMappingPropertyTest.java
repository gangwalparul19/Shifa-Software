package com.shifa.oms.courier;

import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link CourierStatusMapper} (Req 10.3, 11.1, 11.2;
 * design &sect;6.6, Property 15).
 *
 * <p>The mapping must be <em>total over its vocabulary</em>: every recognised
 * courier token — including the two new delivery-failure outcomes
 * {@code CUSTOMER_REJECTED} and {@code DELIVERY_FAILED} — maps to its specified
 * internal status regardless of case or separator ({@code -}/space treated as
 * {@code _}), and any unrecognised or blank token maps to empty.
 *
 * <p>These properties are pure and in-memory: no Spring, database, or Mockito
 * mocks of concrete classes. jqwik runs the default 1000 tries, comfortably above
 * the required minimum of 100 iterations.
 */
class CourierStatusMappingPropertyTest {

    /**
     * The recognised courier vocabulary, restated independently of the production
     * {@link CourierStatusMapper} so the property genuinely checks conformance to
     * the design §6.6 specification (canonical, underscore-separated tokens).
     */
    private static final Map<String, OrderStatus> VOCABULARY = vocabulary();

    private static Map<String, OrderStatus> vocabulary() {
        Map<String, OrderStatus> v = new LinkedHashMap<>();
        v.put("pickup", OrderStatus.DISPATCHED);
        v.put("picked_up", OrderStatus.DISPATCHED);
        v.put("dispatched", OrderStatus.DISPATCHED);
        v.put("in_transit", OrderStatus.IN_TRANSIT);
        v.put("out_for_delivery", OrderStatus.OUT_FOR_DELIVERY);
        v.put("delivered", OrderStatus.DELIVERED);
        v.put("return", OrderStatus.RTO);
        v.put("returned", OrderStatus.RTO);
        v.put("rto", OrderStatus.RTO);
        v.put("lost", OrderStatus.COURIER_LOST);
        v.put("damaged", OrderStatus.COURIER_LOST);
        v.put("missing", OrderStatus.COURIER_LOST);
        // New delivery-failure outcomes (Req 11.1, 11.2).
        v.put("customer_rejected", OrderStatus.CUSTOMER_REJECTED);
        v.put("refused", OrderStatus.CUSTOMER_REJECTED);
        v.put("rejected", OrderStatus.CUSTOMER_REJECTED);
        v.put("delivery_failed", OrderStatus.DELIVERY_FAILED);
        v.put("failed", OrderStatus.DELIVERY_FAILED);
        v.put("undelivered", OrderStatus.DELIVERY_FAILED);
        v.put("attempt_failed", OrderStatus.DELIVERY_FAILED);
        return v;
    }

    /** The set of normalized recognised tokens, for the unrecognised-token filter. */
    private static final Set<String> KNOWN = VOCABULARY.keySet();

    // Feature: role-based-order-workflow, Property 15: Courier status mapping is total over its vocabulary
    // **Validates: Requirements 10.3, 11.1, 11.2**
    @Property
    void recognisedTokensMapToSpecifiedStatusIrrespectiveOfCaseAndSeparator(
            @ForAll("recognisedPerturbations") Tuple2<String, OrderStatus> sample) {
        Optional<OrderStatus> mapped = CourierStatusMapper.toInternal(sample.get1());

        // Every recognised token (any case, any separator, any surrounding
        // whitespace) maps to exactly the specified internal status.
        assertThat(mapped).contains(sample.get2());
    }

    // Feature: role-based-order-workflow, Property 15: Courier status mapping is total over its vocabulary
    // **Validates: Requirements 10.3, 11.1, 11.2**
    @Property
    void unrecognisedTokensMapToEmpty(@ForAll("unrecognisedTokens") String token) {
        // Anything outside the vocabulary is ignored (empty) — never a wrong status.
        assertThat(CourierStatusMapper.toInternal(token)).isEmpty();
    }

    // Feature: role-based-order-workflow, Property 15: Courier status mapping is total over its vocabulary
    // **Validates: Requirements 10.3, 11.1, 11.2**
    @Property
    void blankOrWhitespaceTokensMapToEmpty(@ForAll("blankTokens") String blank) {
        assertThat(CourierStatusMapper.toInternal(blank)).isEmpty();
    }

    // --- Generators ---------------------------------------------------------

    /**
     * A recognised token together with its expected status, perturbed by random
     * case, separator ({@code _}/{@code -}/space), and surrounding whitespace — all
     * of which the mapper must treat identically.
     */
    @Provide
    Arbitrary<Tuple2<String, OrderStatus>> recognisedPerturbations() {
        Arbitrary<Map.Entry<String, OrderStatus>> entries =
                Arbitraries.of(VOCABULARY.entrySet());
        Arbitrary<Character> separators = Arbitraries.of('_', '-', ' ');
        Arbitrary<Integer> leadPad = Arbitraries.integers().between(0, 3);
        Arbitrary<Integer> trailPad = Arbitraries.integers().between(0, 3);
        Arbitrary<Long> caseSeed = Arbitraries.longs();

        return Combinators.combine(entries, separators, leadPad, trailPad, caseSeed)
                .as((entry, sep, lead, trail, seed) -> {
                    String canonical = entry.getKey();
                    String separated = canonical.replace('_', sep);
                    String cased = randomizeCase(separated, seed);
                    String padded = " ".repeat(lead) + cased + " ".repeat(trail);
                    return Tuple.of(padded, entry.getValue());
                });
    }

    /**
     * Arbitrary tokens that are not in the recognised vocabulary once normalized
     * (lower-cased, separators unified) — these must map to empty.
     */
    @Provide
    Arbitrary<String> unrecognisedTokens() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1).ofMaxLength(24)
                .map(s -> s + "_x")
                .filter(s -> {
                    String normalized = s.trim().toLowerCase(Locale.ROOT)
                            .replace('-', '_').replace(' ', '_');
                    return !normalized.isBlank() && !KNOWN.contains(normalized);
                });
    }

    /** Blank tokens: empty, pure whitespace, or {@code null}. */
    @Provide
    Arbitrary<String> blankTokens() {
        Arbitrary<String> whitespace = Arbitraries.of(" ", "   ", "\t", "\n", " \t ", "");
        return Arbitraries.oneOf(whitespace, Arbitraries.just(null));
    }

    private static String randomizeCase(String value, long seed) {
        StringBuilder sb = new StringBuilder(value.length());
        long bits = seed;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean upper = (bits & 1L) == 1L;
            bits >>= 1;
            sb.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
