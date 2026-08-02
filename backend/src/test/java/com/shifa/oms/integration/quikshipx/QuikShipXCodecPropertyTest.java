package com.shifa.oms.integration.quikshipx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.integration.MalformedPayloadException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature: shopify-quikshipx-order-sync, Property 13: Shipment payloads round-trip,
 * Property 14: parsing is strict on required fields and tolerant of unknown ones, and
 * Property 38: a tolerant acceptance never loses the order reference.
 *
 * <p>For any submission model, serializing and re-parsing yields an equal model; for any
 * create-order response — including one carrying none of the recognised identifier keys —
 * an accepted submission still yields the reference we sent.
 *
 * <p>Validates: Requirements 5.5, 5.12, 8.1, 8.2, 8.5, 8.7, 8.8
 */
class QuikShipXCodecPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final QuikShipXProperties.ResponseKeys KEYS =
            new QuikShipXProperties.ResponseKeys(null, null, null, null, null, null);

    // --- Property 13: round trip -------------------------------------------

    @Property(tries = 500)
    void serializingThenParsingYieldsAnEqualSubmission(
            @ForAll("submissions") ShipmentSubmission original) {

        ShipmentSubmission reparsed = QuikShipXSubmissionCodec.parse(
                QuikShipXSubmissionCodec.serialize(original));

        // Field-for-field, including line items by count and position. A dropped or
        // renamed key would surface here rather than as a courier-side rejection.
        assertThat(reparsed).isEqualTo(original);
    }

    @Property(tries = 300)
    void everyValueIsWrittenAsAJsonString(@ForAll("submissions") ShipmentSubmission submission) throws Exception {
        JsonNode root = MAPPER.readTree(QuikShipXSubmissionCodec.serialize(submission));

        // QuikShipX quotes amounts, dimensions and quantities alike. Emitting a JSON
        // number instead changes the wire type.
        assertAllStrings(root.get("customer_details"));
        assertAllStrings(root.get("shipment_details"));
        assertAllStrings(root.get("shipper_details"));
        for (JsonNode item : root.get("product_details")) {
            assertAllStrings(item);
        }
    }

    @Property(tries = 300)
    void theBodyCarriesEveryDocumentedKey(@ForAll("submissions") ShipmentSubmission submission) throws Exception {
        JsonNode root = MAPPER.readTree(QuikShipXSubmissionCodec.serialize(submission));

        assertThat(fieldNames(root)).containsExactlyInAnyOrder(
                "customer_details", "shipment_details", "product_details", "shipper_details");

        assertThat(fieldNames(root.get("customer_details"))).containsExactlyInAnyOrder(
                "customer_full_name", "customer_phone_number", "customer_full_address",
                "customer_pincode", "customer_order_id", "customer_order_date",
                "customer_address_type", "customer_email_id",
                "customer_alternate_phone_number", "customer_landmark");

        assertThat(fieldNames(root.get("shipment_details"))).containsExactlyInAnyOrder(
                "shipment_package_type", "shipment_dead_weight_in_grams",
                "shipment_length", "shipment_width", "shipment_height",
                "shipment_pickup_warehouse_id", "shipment_shipping_mode", "shipment_pay_mode",
                "order_amount", "cod_amount", "commodity_amount", "shipping_amount",
                "discount_amount", "discount_coupon_name");

        assertThat(fieldNames(root.get("shipper_details")))
                .containsExactlyInAnyOrder("client_code", "user_id", "user_secret");
    }

    // --- Property 14: strict on required, tolerant of unknown --------------

    @Property(tries = 200)
    void unknownFieldsAreIgnored(@ForAll("submissions") ShipmentSubmission original) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        MAPPER.readTree(QuikShipXSubmissionCodec.serialize(original));

        // QuikShipX adding a field must not break us.
        root.put("some_new_top_level_field", "value");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("customer_details"))
                .put("customer_middle_name", "ignored");

        assertThat(QuikShipXSubmissionCodec.parse(MAPPER.writeValueAsString(root))).isEqualTo(original);
    }

    @Test
    void anAbsentRequiredSectionIsMalformedAndNamesTheField() {
        assertThatThrownBy(() -> QuikShipXSubmissionCodec.parse("{\"shipment_details\":{}}"))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessageContaining("customer_details");

        assertThatThrownBy(() -> QuikShipXSubmissionCodec.parse("{\"customer_details\":{}}"))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessageContaining("shipment_details");

        assertThatThrownBy(() -> QuikShipXSubmissionCodec.parse("not json"))
                .isInstanceOf(MalformedPayloadException.class);

        assertThatThrownBy(() -> QuikShipXSubmissionCodec.parse("  "))
                .isInstanceOf(MalformedPayloadException.class);
    }

    @Test
    void credentialsAreMaskedByRedacted() {
        ShipmentSubmission submission = submission(1, "SHIFA-SHR-1");

        ShipmentSubmission redacted = submission.redacted();

        // A raw submission must never reach a log line or an integration_events row.
        assertThat(redacted.shipperDetails().userSecret()).isEqualTo("***");
        assertThat(redacted.shipperDetails().clientCode()).isEqualTo("***");
        assertThat(redacted.shipperDetails().userId()).isEqualTo("***");
        // Everything else survives, so a redacted copy is still diagnostically useful.
        assertThat(redacted.customerDetails()).isEqualTo(submission.customerDetails());
        assertThat(redacted.orderReference()).isEqualTo("SHIFA-SHR-1");
    }

    // --- Property 38: tolerant acceptance ----------------------------------

    @Property(tries = 300)
    void anAcceptanceAlwaysKeepsTheReferenceWeSent(
            @ForAll @IntRange(min = 1, max = 9999) int suffix,
            @ForAll boolean test) {

        String reference = "SHIFA-SHR-" + suffix;

        // Bodies that tell us nothing: empty, null, non-JSON, JSON with no known keys.
        for (String body : List.of("", "   ", "not json at all", "{}", "{\"ok\":true}", "[]")) {
            ShipmentAcceptance acceptance =
                    QuikShipXAcceptanceCodec.parse(reference, test, body, KEYS);

            // A 2xx means QuikShipX has the shipment. Failing here would strand the
            // order at APPROVED and retry pointlessly.
            assertThat(acceptance.orderReference()).isEqualTo(reference);
            assertThat(acceptance.test()).isEqualTo(test);
            assertThat(acceptance.hasAnyIdentifier()).isFalse();
            assertThat(acceptance.shipmentIdValue()).isEmpty();
            assertThat(acceptance.awbValue()).isEmpty();
        }

        ShipmentAcceptance fromNull = QuikShipXAcceptanceCodec.parse(reference, test, null, KEYS);
        assertThat(fromNull.orderReference()).isEqualTo(reference);
    }

    @Test
    void identifiersAreFoundUnderAnyCandidateKeyAndAtAnyDepth() {
        // Flat, snake_case.
        assertThat(QuikShipXAcceptanceCodec.parse("SHIFA-1", false,
                "{\"shipment_id\":\"S1\",\"awb\":\"A1\"}", KEYS).shipmentIdValue()).contains("S1");

        // camelCase alternative.
        assertThat(QuikShipXAcceptanceCodec.parse("SHIFA-1", false,
                "{\"shipmentId\":\"S2\"}", KEYS).shipmentIdValue()).contains("S2");

        // Wrapped in a data envelope — a very common provider shape.
        ShipmentAcceptance nested = QuikShipXAcceptanceCodec.parse("SHIFA-1", false,
                "{\"status\":\"ok\",\"data\":{\"shipment\":{\"waybill\":\"A9\",\"courier_name\":\"Delhivery\"}}}",
                KEYS);
        assertThat(nested.awbValue()).contains("A9");
        assertThat(nested.courierNameValue()).contains("Delhivery");
        assertThat(nested.hasAnyIdentifier()).isTrue();

        // Blank values are treated as absent, not as an empty identifier.
        assertThat(QuikShipXAcceptanceCodec.parse("SHIFA-1", false,
                "{\"awb\":\"   \"}", KEYS).awbValue()).isEmpty();
    }

    @Test
    void theTwoDistinctQuikShipXIdentifiersAreCapturedSeparately() {
        // The exact success shape from QuikShipX: `id` is the shipment/tracking id and
        // `order_id` is their own order id, and they must not be conflated.
        String body = "{\"response\":[{\"id\":65580852235,\"status\":\"success\",\"order_id\":177286}]}";

        ShipmentAcceptance acceptance = QuikShipXAcceptanceCodec.parse("SHIFA-1", true, body, KEYS);

        assertThat(acceptance.shipmentIdValue()).contains("65580852235");
        assertThat(acceptance.quikshipxOrderIdValue()).contains("177286");
        // No AWB in the create response — it is issued later.
        assertThat(acceptance.awbValue()).isEmpty();
    }

    @Test
    void candidateKeyOrderDecidesWhichValueWins() {
        // shipment_id is listed before id, so it wins even though both are present.
        ShipmentAcceptance acceptance = QuikShipXAcceptanceCodec.parse("SHIFA-1", false,
                "{\"id\":\"WRONG\",\"shipment_id\":\"RIGHT\"}", KEYS);

        assertThat(acceptance.shipmentIdValue()).contains("RIGHT");
    }

    @Test
    void theRawResponseIsRetainedForPinningTheRealKeyNames() {
        String body = "{\"weird_provider_key\":\"X1\"}";

        ShipmentAcceptance acceptance = QuikShipXAcceptanceCodec.parse("SHIFA-1", true, body, KEYS);

        // This is how the real field names get discovered from production traffic.
        assertThat(acceptance.rawResponse()).isEqualTo(body);
        assertThat(acceptance.test()).isTrue();
    }

    @Test
    void aStatusFailureBodyIsDetectedAsARejectionNotAnAcceptance() {
        // The exact shape observed from QuikShipX: HTTP 200, but the body is a failure with
        // per-product HSN errors. Treating this as success recorded a phantom shipment.
        String body = "{\"response\":[{\"id\":87491524223,\"status\":\"failure\","
                + "\"errors\":[\"Error : PID(1) HSN Code must be greater than 1 character\","
                + "\"Error : PID(2) HSN Code must be greater than 1 character\"]}]}";

        List<String> errors = QuikShipXAcceptanceCodec.detectFailure(body);

        assertThat(errors).hasSize(2);
        assertThat(errors.get(0)).contains("HSN Code must be greater than 1 character");
    }

    @Test
    void aNonEmptyErrorsArrayAloneIsAFailureEvenWithoutAStatusField() {
        assertThat(QuikShipXAcceptanceCodec.detectFailure("{\"errors\":[\"bad pincode\"]}"))
                .containsExactly("bad pincode");
        // status=failure with no errors array still counts, named generically.
        assertThat(QuikShipXAcceptanceCodec.detectFailure("{\"status\":\"failure\"}"))
                .hasSize(1);
    }

    @Test
    void aDuplicateOrderIdErrorIsRecognisedAsAlreadyExistingNotAHardFailure() {
        // QuikShipX enforces uniqueness on customer_order_id; a re-send or a race with the
        // auto-publish drainer produces this, and it means the order is already booked.
        String body = "{\"response\":[{\"status\":\"failure\","
                + "\"errors\":[\"Error : Client Order ID Already Exists\"]}]}";

        List<String> errors = QuikShipXAcceptanceCodec.detectFailure(body);
        assertThat(errors).containsExactly("Error : Client Order ID Already Exists");
        assertThat(QuikShipXAcceptanceCodec.isDuplicateReference(errors)).isTrue();

        // A genuine field rejection is NOT a duplicate.
        assertThat(QuikShipXAcceptanceCodec.isDuplicateReference(
                List.of("Error : PID(1) HSN Code must be greater than 1 character"))).isFalse();
        assertThat(QuikShipXAcceptanceCodec.isDuplicateReference(List.of())).isFalse();
    }

    @Test
    void anAcceptanceBodyIsNotMistakenForAFailure() {
        // Success shapes must yield NO failure, even when they carry a scalar "message".
        for (String ok : List.of(
                "{\"status\":\"success\",\"shipment_id\":\"S1\"}",
                "{\"message\":\"Order created\",\"awb\":\"A1\"}",
                "{\"response\":[{\"id\":123,\"status\":\"success\"}]}",
                "{}", "", "not json")) {
            assertThat(QuikShipXAcceptanceCodec.detectFailure(ok))
                    .as("body: %s", ok)
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Generators and helpers
    // ------------------------------------------------------------------

    @Provide
    Arbitrary<ShipmentSubmission> submissions() {
        Arbitrary<Integer> suffixes = Arbitraries.integers().between(1, 9999);
        Arbitrary<Integer> itemCounts = Arbitraries.integers().between(0, 4);
        return Combinators.combine(suffixes, itemCounts)
                .as((suffix, items) -> submission(items, "SHIFA-SHR-" + suffix));
    }

    private static ShipmentSubmission submission(int itemCount, String reference) {
        List<ShipmentSubmission.ProductDetail> items = new java.util.ArrayList<>();
        for (int i = 1; i <= itemCount; i++) {
            items.add(new ShipmentSubmission.ProductDetail(
                    "Product " + i, "Herbal", "SKU-" + i, "5.00", "3004", "199.00", "0", String.valueOf(i)));
        }
        return new ShipmentSubmission(
                new ShipmentSubmission.CustomerDetails(
                        "Asha Kumar", "9812345678", "12 MG Road, Pune, Maharashtra", "411001",
                        reference, "14 March 2026", "1", "", "", ""),
                new ShipmentSubmission.ShipmentDetails(
                        "1", "500", "10", "10", "10", "65", "1", "2",
                        "999.00", "0", "999.00", "0.00", "0.00", ""),
                items,
                new ShipmentSubmission.ShipperDetails("CLIENT", "USER", "SECRET"));
    }

    private static void assertAllStrings(JsonNode node) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            assertThat(entry.getValue().isTextual())
                    .as("%s must be serialized as a JSON string", entry.getKey())
                    .isTrue();
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
