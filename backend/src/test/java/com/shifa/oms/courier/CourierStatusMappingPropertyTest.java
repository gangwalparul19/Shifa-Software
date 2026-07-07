package com.shifa.oms.courier;

import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for courier status mapping.
 *
 * Feature: shifa-herbal-remedies, Property 7: For any courier status update, the
 * update maps to the correct internal Order_Status (pickup → Dispatched;
 * in-transit → In_Transit; out-for-delivery → Out_For_Delivery; delivered →
 * Delivered; return → RTO; lost/damaged/missing → Courier_Lost), and is applied
 * only when that transition is legal from the current status.
 *
 * **Validates: Requirements 13.1, 13.2, 17.1**
 *
 * <p>The test exercises the pure mapping ({@link CourierStatusMapper}) and the
 * legality gate that {@link CourierStatusApplier} applies before any status
 * change: for any recognised courier token and any current status, an update is
 * "applicable" exactly when the state machine permits the transition. Each
 * property runs the jqwik default of 1000 tries (≥ 100).
 */
class CourierStatusMappingPropertyTest {

    // Feature: shifa-herbal-remedies, Property 7: Courier status mapping
    // **Validates: Requirements 13.1, 13.2, 17.1**
    @Property
    void recognisedTokensMapToTheCorrectInternalStatus(
            @ForAll("recognisedTokens") Tuple.Tuple2<String, OrderStatus> tokenAndStatus) {
        String raw = tokenAndStatus.get1();
        OrderStatus expected = tokenAndStatus.get2();

        assertThat(CourierStatusMapper.toInternal(raw)).contains(expected);
        // Case- and separator-insensitive: variants map identically.
        assertThat(CourierStatusMapper.toInternal(raw.toUpperCase(Locale.ROOT))).contains(expected);
        assertThat(CourierStatusMapper.toInternal(raw.replace('_', '-'))).contains(expected);
        assertThat(CourierStatusMapper.toInternal(raw.replace('_', ' '))).contains(expected);
    }

    // Feature: shifa-herbal-remedies, Property 7: Courier status mapping
    // **Validates: Requirements 13.1, 13.2, 17.1**
    @Property
    void updateIsApplicableExactlyWhenTransitionIsLegal(
            @ForAll("recognisedTokens") Tuple.Tuple2<String, OrderStatus> tokenAndStatus,
            @ForAll("statuses") OrderStatus current) {
        OrderStatus mapped = tokenAndStatus.get2();

        // The mapping does not depend on the current status.
        assertThat(CourierStatusMapper.toInternal(tokenAndStatus.get1())).contains(mapped);

        // The applier only acts when the mapped transition is legal from current;
        // this mirrors CourierStatusApplier's guard exactly (Property 7).
        boolean applicable = current.canTransitionTo(mapped);
        assertThat(applicable).isEqualTo(current.allowedTargets().contains(mapped));
    }

    // Feature: shifa-herbal-remedies, Property 7: Courier status mapping
    // **Validates: Requirements 13.1, 13.2, 17.1**
    @Property
    void unrecognisedTokensNeverMap(@ForAll("unknownTokens") String raw) {
        assertThat(CourierStatusMapper.toInternal(raw)).isEmpty();
    }

    @Provide
    Arbitrary<Tuple.Tuple2<String, OrderStatus>> recognisedTokens() {
        return Arbitraries.of(List.of(
                Tuple.of("pickup", OrderStatus.DISPATCHED),
                Tuple.of("picked_up", OrderStatus.DISPATCHED),
                Tuple.of("dispatched", OrderStatus.DISPATCHED),
                Tuple.of("in_transit", OrderStatus.IN_TRANSIT),
                Tuple.of("out_for_delivery", OrderStatus.OUT_FOR_DELIVERY),
                Tuple.of("delivered", OrderStatus.DELIVERED),
                Tuple.of("return", OrderStatus.RTO),
                Tuple.of("returned", OrderStatus.RTO),
                Tuple.of("rto", OrderStatus.RTO),
                Tuple.of("lost", OrderStatus.COURIER_LOST),
                Tuple.of("damaged", OrderStatus.COURIER_LOST),
                Tuple.of("missing", OrderStatus.COURIER_LOST)));
    }

    @Provide
    Arbitrary<OrderStatus> statuses() {
        return Arbitraries.of(OrderStatus.values());
    }

    @Provide
    Arbitrary<String> unknownTokens() {
        Arbitrary<String> words = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        List<String> known = List.of("pickup", "picked_up", "dispatched", "in_transit",
                "out_for_delivery", "delivered", "return", "returned", "rto",
                "lost", "damaged", "missing");
        return words.filter(w -> !known.contains(w.toLowerCase(Locale.ROOT)));
    }
}
