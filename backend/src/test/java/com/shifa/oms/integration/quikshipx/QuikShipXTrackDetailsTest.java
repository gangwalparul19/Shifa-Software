package com.shifa.oms.integration.quikshipx;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the lifecycle-timeline parser against the confirmed QuikShipX track-order
 * response, so the order drawer can show the full journey from Shifa's own portal.
 */
class QuikShipXTrackDetailsTest {

    private static final String REAL_RESPONSE = "{\"response\":[{\"request_id\":18064554903,"
            + "\"shipment_details\":{\"tracking_no\":\"20736021008546\",\"order_status_id\":\"3\","
            + "\"order_status\":\"tracking id assigned\",\"courier_name\":\"Delhivery_Surface\","
            + "\"booked_on_datetime\":\"2026-08-14 11:45:44\","
            + "\"confirmed_on_datetime\":\"2026-08-14 11:52:24\","
            + "\"tracking_id_assigned_on_datetime\":\"2026-08-14 11:52:51\","
            + "\"label_printed_on_datetime\":null,\"courier_picked_up_on_datetime\":null,"
            + "\"courier_delivered_on_datetime\":null},"
            + "\"shipment_scanning\":{\"1\":{\"status_code_2\":\"Manifested\","
            + "\"scan_dt\":\"2026-08-14 11:53:00\",\"location\":\"Indore_Dakachya_GW (Madhya Pradesh)\","
            + "\"instructions\":\"BadIncomplete Address\"},"
            + "\"2\":{\"status_code_2\":\"Manifested\",\"scan_dt\":\"2026-08-14 11:52:51\","
            + "\"location\":\"Indore_Dakachya_GW (Madhya Pradesh)\",\"instructions\":\"Manifest uploaded\"}}}]}";

    @Test
    void parsesCarrierStatusAwbAndTheOccurredStagesInOrder() {
        QuikShipXTrackDetails details = QuikShipXTrackDetails.parse(REAL_RESPONSE);

        assertThat(details.courierName()).isEqualTo("Delhivery_Surface");
        assertThat(details.currentStatus()).isEqualTo("tracking id assigned");
        assertThat(details.awb()).isEqualTo("20736021008546");
        // Only the three stages with a timestamp are surfaced, in lifecycle order.
        assertThat(details.timeline()).extracting(QuikShipXTrackDetails.Stage::label)
                .containsExactly("Pending", "Confirmed", "Tracking ID Assigned");
    }

    @Test
    void ordersScansMostRecentFirst() {
        QuikShipXTrackDetails details = QuikShipXTrackDetails.parse(REAL_RESPONSE);

        assertThat(details.scans()).hasSize(2);
        // 11:53:00 is newer than 11:52:51, so it comes first.
        assertThat(details.scans().get(0).at().getMinute()).isEqualTo(53);
        assertThat(details.scans().get(0).location()).contains("Indore");
    }

    @Test
    void aBlankOrUnparseableResponseYieldsEmptyNotAnError() {
        assertThat(QuikShipXTrackDetails.parse(null)).isSameAs(QuikShipXTrackDetails.EMPTY);
        assertThat(QuikShipXTrackDetails.parse("not-json").timeline()).isEmpty();
    }
}
