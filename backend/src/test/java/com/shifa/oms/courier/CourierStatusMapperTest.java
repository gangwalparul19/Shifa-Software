package com.shifa.oms.courier;

import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example-based unit tests for {@link CourierStatusMapper} (Req 13.1, 13.2, 17.1),
 * covering the recognised vocabulary, separator/case-insensitivity, and unknown
 * tokens.
 */
class CourierStatusMapperTest {

    @Test
    void mapsRecognisedTokensToInternalStatuses() {
        assertThat(CourierStatusMapper.toInternal("pickup")).contains(OrderStatus.DISPATCHED);
        assertThat(CourierStatusMapper.toInternal("in_transit")).contains(OrderStatus.IN_TRANSIT);
        assertThat(CourierStatusMapper.toInternal("out_for_delivery")).contains(OrderStatus.OUT_FOR_DELIVERY);
        assertThat(CourierStatusMapper.toInternal("delivered")).contains(OrderStatus.DELIVERED);
        assertThat(CourierStatusMapper.toInternal("rto")).contains(OrderStatus.RTO);
        assertThat(CourierStatusMapper.toInternal("return")).contains(OrderStatus.RTO);
        assertThat(CourierStatusMapper.toInternal("lost")).contains(OrderStatus.REDISPATCH);
        assertThat(CourierStatusMapper.toInternal("damaged")).contains(OrderStatus.REDISPATCH);
        assertThat(CourierStatusMapper.toInternal("missing")).contains(OrderStatus.REDISPATCH);
    }

    @Test
    void isCaseAndSeparatorInsensitive() {
        assertThat(CourierStatusMapper.toInternal("Out-For-Delivery")).contains(OrderStatus.OUT_FOR_DELIVERY);
        assertThat(CourierStatusMapper.toInternal("IN TRANSIT")).contains(OrderStatus.IN_TRANSIT);
        assertThat(CourierStatusMapper.toInternal("  Delivered  ")).contains(OrderStatus.DELIVERED);
    }

    @Test
    void unknownOrBlankTokensMapToEmpty() {
        assertThat(CourierStatusMapper.toInternal("teleported")).isEmpty();
        assertThat(CourierStatusMapper.toInternal("")).isEmpty();
        assertThat(CourierStatusMapper.toInternal(null)).isEmpty();
    }
}
