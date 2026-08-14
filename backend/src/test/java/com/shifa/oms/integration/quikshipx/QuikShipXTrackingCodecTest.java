package com.shifa.oms.integration.quikshipx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.integration.quikshipx.QuikShipXProperties.ResponseKeys;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link QuikShipXTrackingCodec} — the track-order request builder
 * and the defensive (candidate-key, deep-search) response parser.
 */
class QuikShipXTrackingCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ResponseKeys KEYS =
            new ResponseKeys(null, null, null, null, null, null, null, null);

    @Test
    void buildsTheConfirmedRequestShape() throws Exception {
        String body = QuikShipXTrackingCodec.buildRequestBody(
                "BUT051", "123", "secret-xyz", "2525261531512", "awb");
        JsonNode root = MAPPER.readTree(body);

        assertThat(root.path("tracking_details").path("tracking_no").asText())
                .isEqualTo("2525261531512");
        assertThat(root.path("tracking_details").path("tracking_type").asText()).isEqualTo("awb");
        assertThat(root.path("shipper_details").path("client_code").asText()).isEqualTo("BUT051");
        assertThat(root.path("shipper_details").path("user_id").asText()).isEqualTo("123");
        assertThat(root.path("shipper_details").path("user_secret").asText()).isEqualTo("secret-xyz");
    }

    @Test
    void normalizesUnknownTypeToAwbAndKeepsOrderId() {
        assertThat(QuikShipXTrackingCodec.normalizeType("weird")).isEqualTo("awb");
        assertThat(QuikShipXTrackingCodec.normalizeType(null)).isEqualTo("awb");
        assertThat(QuikShipXTrackingCodec.normalizeType("order_id")).isEqualTo("order_id");
    }

    @Test
    void parsesStatusFromTopLevel() {
        String json = "{\"status\":\"In Transit\",\"awb\":\"2525261531512\"}";
        QuikShipXStatusEvent event = QuikShipXTrackingCodec.parse(json, KEYS, "SHIFA-SHR-1");
        assertThat(event.statusToken()).isEqualTo("In Transit");
        assertThat(event.awb()).isEqualTo("2525261531512");
        assertThat(event.orderReference()).isEqualTo("SHIFA-SHR-1");
    }

    @Test
    void parsesStatusFromNestedAndArrayShapes() {
        String nested = "{\"data\":{\"current_status\":\"Delivered\",\"awb_number\":\"999\"}}";
        assertThat(QuikShipXTrackingCodec.parse(nested, KEYS, null).statusToken()).isEqualTo("Delivered");

        String array = "{\"tracking\":[{\"shipment_status\":\"Out For Delivery\"}]}";
        assertThat(QuikShipXTrackingCodec.parse(array, KEYS, null).statusToken())
                .isEqualTo("Out For Delivery");
    }

    @Test
    void parsesStatusTimestampWhenPresent() {
        String json = "{\"status\":\"In Transit\",\"status_date\":\"2026-08-13 14:05:00\"}";
        QuikShipXStatusEvent event = QuikShipXTrackingCodec.parse(json, KEYS, null);
        assertThat(event.statusAt()).isNotNull();
        assertThat(event.statusAt().getHour()).isEqualTo(14);
    }

    @Test
    void parsesTheConfirmedQuikShipXTrackResponseShape() {
        // The real track-order success body (captured from QuikShipX): the AWB is
        // shipment_details.tracking_no and the status is shipment_details.order_status.
        String json = "{\"response\":[{\"request_id\":18064554903,\"shipment_details\":{"
                + "\"tracking_no\":\"20736021008546\",\"order_status_id\":\"3\","
                + "\"order_status\":\"tracking id assigned\",\"courier_name\":\"Delhivery_Surface\"}}]}";

        QuikShipXStatusEvent event = QuikShipXTrackingCodec.parse(json, KEYS, "SHIFA-SHR-X");

        assertThat(event.statusToken()).isEqualTo("tracking id assigned");
        assertThat(event.awb()).isEqualTo("20736021008546");
        assertThat(event.orderReference()).isEqualTo("SHIFA-SHR-X");
    }

    @Test
    void missingStatusYieldsNullTokenNotAnError() {
        String json = "{\"message\":\"ok\"}";
        QuikShipXStatusEvent event = QuikShipXTrackingCodec.parse(json, KEYS, "SHIFA-1");
        assertThat(event.statusToken()).isNull();
        assertThat(event.orderReference()).isEqualTo("SHIFA-1");
    }

    @Test
    void malformedJsonThrows() {
        assertThatThrownBy(() -> QuikShipXTrackingCodec.parse("not-json", KEYS, null))
                .isInstanceOf(QuikShipXTrackingCodec.MalformedTrackingResponse.class);
    }
}
