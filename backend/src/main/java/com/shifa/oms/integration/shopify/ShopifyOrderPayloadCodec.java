package com.shifa.oms.integration.shopify;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shifa.oms.integration.MalformedPayloadException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a Shopify order webhook body into a {@link ShopifyOrderModel}, and writes one back
 * (Req 8.4, 8.6, 8.7, 8.8).
 *
 * <p>Tolerant where Shopify is variable, strict only where Shifa genuinely cannot proceed:
 *
 * <ul>
 *   <li><b>Unknown fields are ignored.</b> Shopify order payloads carry a hundred-plus
 *       fields and add more between API versions; failing on an unrecognised one would break
 *       ingestion on their schedule rather than ours (Req 8.8).</li>
 *   <li><b>Only the order id is required.</b> Without it there is no idempotency key, so a
 *       retry would create duplicate orders — that is the one thing worth rejecting for
 *       (Req 8.7). Everything else degrades into a review reason.</li>
 *   <li><b>The customer name is assembled from several possible places</b>, because Shopify
 *       puts it in {@code customer}, in {@code shipping_address}, or only in
 *       {@code billing_address} depending on how the order was placed.</li>
 * </ul>
 *
 * <p>{@link #serialize} exists so the round-trip is property-testable; live traffic only
 * ever parses.
 *
 * <p>Pure: no Spring, no I/O, no clock.
 */
public final class ShopifyOrderPayloadCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ShopifyOrderPayloadCodec() {
        // Pure static helper.
    }

    /**
     * @throws MalformedPayloadException when the body is not JSON, or carries no order id
     */
    public static ShopifyOrderModel parse(String json) {
        JsonNode root = readTree(json);

        String orderId = firstText(root, "id", "order_id", "admin_graphql_api_id");
        if (orderId.isEmpty()) {
            throw new MalformedPayloadException("id", "the Shopify order payload carries no order id");
        }

        JsonNode shipping = root.path("shipping_address");
        JsonNode billing = root.path("billing_address");
        JsonNode customer = root.path("customer");
        // Prefer the shipping address; a digital-only order may only have billing.
        JsonNode addressNode = shipping.isObject() ? shipping : billing;

        return new ShopifyOrderModel(
                orderId,
                firstText(root, "name", "order_number", "number"),
                customerName(customer, addressNode),
                firstText(addressNode, "phone").isEmpty()
                        ? firstText(root, "phone", "contact_phone") : firstText(addressNode, "phone"),
                firstText(root, "email", "contact_email"),
                address(addressNode),
                lineItems(root.path("line_items")),
                decimal(root, "total_price", "current_total_price"),
                firstText(root, "currency", "presentment_currency"),
                firstText(root, "financial_status"));
    }

    /** Writes a model back out in Shopify's shape, for the round-trip property. */
    public static String serialize(ShopifyOrderModel model) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", model.shopifyOrderId());
        root.put("name", model.shopifyOrderNumber());
        root.put("email", model.email());
        root.put("total_price", model.totalPrice().toPlainString());
        root.put("currency", model.currency());
        root.put("financial_status", model.financialStatus());

        ObjectNode customer = root.putObject("customer");
        String[] names = splitName(model.customerName());
        customer.put("first_name", names[0]);
        customer.put("last_name", names[1]);

        ShopifyOrderModel.Address a = model.address();
        ObjectNode shipping = root.putObject("shipping_address");
        shipping.put("address1", nullToEmpty(a == null ? null : a.addressLine()));
        shipping.put("city", nullToEmpty(a == null ? null : a.city()));
        shipping.put("province", nullToEmpty(a == null ? null : a.state()));
        shipping.put("zip", nullToEmpty(a == null ? null : a.postalCode()));
        shipping.put("country", nullToEmpty(a == null ? null : a.country()));
        shipping.put("phone", nullToEmpty(model.contactNumber()));

        ArrayNode items = root.putArray("line_items");
        for (ShopifyOrderModel.LineItem line : model.lineItems()) {
            ObjectNode item = items.addObject();
            item.put("sku", nullToEmpty(line.sku()));
            item.put("name", nullToEmpty(line.name()));
            item.put("quantity", line.quantity());
            item.put("price", line.unitPrice().toPlainString());
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize the Shopify order model", e);
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            throw new MalformedPayloadException("body", "the Shopify webhook body was empty");
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new MalformedPayloadException("body", "the Shopify webhook body was not a JSON object");
            }
            return root;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new MalformedPayloadException("body", "the Shopify webhook body was not valid JSON");
        }
    }

    /**
     * Shopify puts the buyer's name in up to three places depending on how the order was
     * placed, so try each rather than showing a blank customer in the OMS.
     */
    private static String customerName(JsonNode customer, JsonNode address) {
        String fromCustomer = join(firstText(customer, "first_name"), firstText(customer, "last_name"));
        if (!fromCustomer.isEmpty()) {
            return fromCustomer;
        }
        String fromAddress = join(firstText(address, "first_name"), firstText(address, "last_name"));
        if (!fromAddress.isEmpty()) {
            return fromAddress;
        }
        return firstText(address, "name");
    }

    private static ShopifyOrderModel.Address address(JsonNode node) {
        if (!node.isObject()) {
            return new ShopifyOrderModel.Address("", "", "", "", "");
        }
        // address2 is a genuine second line (flat / building), so append rather than drop it.
        String line1 = firstText(node, "address1");
        String line2 = firstText(node, "address2");
        String line = line2.isEmpty() ? line1 : (line1.isEmpty() ? line2 : line1 + ", " + line2);
        return new ShopifyOrderModel.Address(
                line,
                firstText(node, "city"),
                firstText(node, "province", "province_code", "state"),
                firstText(node, "zip", "postal_code"),
                firstText(node, "country", "country_code"));
    }

    private static List<ShopifyOrderModel.LineItem> lineItems(JsonNode node) {
        List<ShopifyOrderModel.LineItem> items = new ArrayList<>();
        if (!node.isArray()) {
            return items;
        }
        for (JsonNode item : node) {
            items.add(new ShopifyOrderModel.LineItem(
                    firstText(item, "sku", "variant_sku"),
                    firstText(item, "name", "title"),
                    item.path("quantity").asInt(1),
                    decimal(item, "price", "unit_price")));
        }
        return items;
    }

    /** The first non-blank value among the given field names. */
    private static String firstText(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return "";
    }

    /**
     * Reads a money field. Shopify sends amounts as JSON <b>strings</b>, so this goes
     * through {@link BigDecimal} rather than a double — parsing "1234.56" as a double and
     * back would drift.
     */
    private static BigDecimal decimal(JsonNode node, String... fields) {
        String text = firstText(node, fields);
        if (text.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            // A malformed amount is not worth losing the order over; the total-mismatch
            // review reason will flag the discrepancy.
            return BigDecimal.ZERO;
        }
    }

    private static String join(String first, String last) {
        if (first.isEmpty()) {
            return last;
        }
        return last.isEmpty() ? first : first + " " + last;
    }

    private static String[] splitName(String full) {
        if (full == null || full.isBlank()) {
            return new String[]{"", ""};
        }
        String trimmed = full.trim();
        int split = trimmed.indexOf(' ');
        return split < 0
                ? new String[]{trimmed, ""}
                : new String[]{trimmed.substring(0, split), trimmed.substring(split + 1).trim()};
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
