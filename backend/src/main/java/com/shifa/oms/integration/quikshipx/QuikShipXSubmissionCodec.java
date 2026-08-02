package com.shifa.oms.integration.quikshipx;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shifa.oms.integration.MalformedPayloadException;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializes a {@link ShipmentSubmission} into the QuikShipX create-order body and
 * parses it back (contract: {@code docs/QUIKSHIPX-API-V1.md}).
 *
 * <p>The field names are written explicitly rather than derived from the record
 * components by a naming strategy. That is deliberate: QuikShipX's names are
 * inconsistent ({@code shipment_dead_weight_in_grams} but plain {@code order_amount},
 * {@code cod_amount} and {@code commodity_amount} with no {@code shipment_} prefix),
 * so any automatic snake-case mapping would silently emit wrong keys.
 *
 * <p><b>Every value is written as a JSON string</b>, matching the contract's sample
 * request, where amounts, dimensions and quantities are all quoted.
 *
 * <p>Pure: no Spring, no I/O, no clock. {@link #parse} exists so the round-trip is
 * property-testable — it is not used against live QuikShipX traffic.
 */
public final class QuikShipXSubmissionCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private QuikShipXSubmissionCodec() {
        // Pure static helper.
    }

    /** Renders the create-order body. */
    public static String serialize(ShipmentSubmission submission) {
        ObjectNode root = MAPPER.createObjectNode();

        ShipmentSubmission.CustomerDetails c = submission.customerDetails();
        ObjectNode customer = root.putObject("customer_details");
        customer.put("customer_full_name", str(c.customerFullName()));
        customer.put("customer_phone_number", str(c.customerPhoneNumber()));
        customer.put("customer_full_address", str(c.customerFullAddress()));
        customer.put("customer_pincode", str(c.customerPincode()));
        customer.put("customer_order_id", str(c.customerOrderId()));
        customer.put("customer_order_date", str(c.customerOrderDate()));
        customer.put("customer_address_type", str(c.customerAddressType()));
        customer.put("customer_email_id", str(c.customerEmailId()));
        customer.put("customer_alternate_phone_number", str(c.customerAlternatePhoneNumber()));
        customer.put("customer_landmark", str(c.customerLandmark()));

        ShipmentSubmission.ShipmentDetails s = submission.shipmentDetails();
        ObjectNode shipment = root.putObject("shipment_details");
        shipment.put("shipment_package_type", str(s.shipmentPackageType()));
        shipment.put("shipment_dead_weight_in_grams", str(s.shipmentDeadWeightInGrams()));
        shipment.put("shipment_length", str(s.shipmentLength()));
        shipment.put("shipment_width", str(s.shipmentWidth()));
        shipment.put("shipment_height", str(s.shipmentHeight()));
        shipment.put("shipment_pickup_warehouse_id", str(s.shipmentPickupWarehouseId()));
        shipment.put("shipment_shipping_mode", str(s.shipmentShippingMode()));
        shipment.put("shipment_pay_mode", str(s.shipmentPayMode()));
        shipment.put("order_amount", str(s.orderAmount()));
        shipment.put("cod_amount", str(s.codAmount()));
        shipment.put("commodity_amount", str(s.commodityAmount()));
        shipment.put("shipping_amount", str(s.shippingAmount()));
        shipment.put("discount_amount", str(s.discountAmount()));
        shipment.put("discount_coupon_name", str(s.discountCouponName()));

        ArrayNode products = root.putArray("product_details");
        for (ShipmentSubmission.ProductDetail p : submission.productDetails()) {
            ObjectNode item = products.addObject();
            item.put("product_name", str(p.productName()));
            item.put("product_category", str(p.productCategory()));
            item.put("product_sku_code", str(p.productSkuCode()));
            item.put("product_tax_rate", str(p.productTaxRate()));
            item.put("product_hsn_code", str(p.productHsnCode()));
            item.put("product_amount", str(p.productAmount()));
            item.put("product_discount", str(p.productDiscount()));
            item.put("product_quantity", str(p.productQuantity()));
        }

        ShipmentSubmission.ShipperDetails sh = submission.shipperDetails();
        ObjectNode shipper = root.putObject("shipper_details");
        shipper.put("client_code", str(sh == null ? null : sh.clientCode()));
        shipper.put("user_id", str(sh == null ? null : sh.userId()));
        shipper.put("user_secret", str(sh == null ? null : sh.userSecret()));

        try {
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Every node is a plain string, so this is unreachable in practice.
            throw new IllegalStateException("Failed to serialize the QuikShipX submission", e);
        }
    }

    /**
     * Parses a create-order body back into a submission.
     *
     * <p>Unknown fields are ignored, so QuikShipX adding a field does not break us
     * (Req 8.8). A missing section is fatal, because a body without
     * {@code customer_details} is not a create-order request at all.
     */
    public static ShipmentSubmission parse(String json) {
        JsonNode root = readTree(json);

        JsonNode customer = requireSection(root, "customer_details");
        JsonNode shipment = requireSection(root, "shipment_details");
        JsonNode shipper = root.path("shipper_details");

        List<ShipmentSubmission.ProductDetail> products = new ArrayList<>();
        JsonNode productsNode = root.path("product_details");
        if (productsNode.isArray()) {
            for (JsonNode item : productsNode) {
                products.add(new ShipmentSubmission.ProductDetail(
                        text(item, "product_name"),
                        text(item, "product_category"),
                        text(item, "product_sku_code"),
                        text(item, "product_tax_rate"),
                        text(item, "product_hsn_code"),
                        text(item, "product_amount"),
                        text(item, "product_discount"),
                        text(item, "product_quantity")));
            }
        }

        return new ShipmentSubmission(
                new ShipmentSubmission.CustomerDetails(
                        text(customer, "customer_full_name"),
                        text(customer, "customer_phone_number"),
                        text(customer, "customer_full_address"),
                        text(customer, "customer_pincode"),
                        text(customer, "customer_order_id"),
                        text(customer, "customer_order_date"),
                        text(customer, "customer_address_type"),
                        text(customer, "customer_email_id"),
                        text(customer, "customer_alternate_phone_number"),
                        text(customer, "customer_landmark")),
                new ShipmentSubmission.ShipmentDetails(
                        text(shipment, "shipment_package_type"),
                        text(shipment, "shipment_dead_weight_in_grams"),
                        text(shipment, "shipment_length"),
                        text(shipment, "shipment_width"),
                        text(shipment, "shipment_height"),
                        text(shipment, "shipment_pickup_warehouse_id"),
                        text(shipment, "shipment_shipping_mode"),
                        text(shipment, "shipment_pay_mode"),
                        text(shipment, "order_amount"),
                        text(shipment, "cod_amount"),
                        text(shipment, "commodity_amount"),
                        text(shipment, "shipping_amount"),
                        text(shipment, "discount_amount"),
                        text(shipment, "discount_coupon_name")),
                products,
                new ShipmentSubmission.ShipperDetails(
                        text(shipper, "client_code"),
                        text(shipper, "user_id"),
                        text(shipper, "user_secret")));
    }

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            throw new MalformedPayloadException("body", "the QuikShipX submission body was empty");
        }
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new MalformedPayloadException("body", "the QuikShipX submission body was not valid JSON");
        }
    }

    private static JsonNode requireSection(JsonNode root, String name) {
        JsonNode section = root.path(name);
        if (section.isMissingNode() || section.isNull() || !section.isObject()) {
            throw new MalformedPayloadException(name, "section is absent");
        }
        return section;
    }

    /** Reads a field as text, treating absent as empty so the round trip is stable. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }
}
