package com.shifa.oms.integration.quikshipx;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-flight validation of a {@link ShipmentSubmission} against the rules QuikShipX is
 * known to enforce (spec {@code shopify-quikshipx-order-sync}, Req 16.6).
 *
 * <p>The point of this class is to fail <b>once, completely and legibly</b> instead of the
 * network round-trip surfacing one missing field at a time. QuikShipX validates
 * field-by-field and returns the first problem it hits, so without this an admin fixes an
 * HSN, retries, learns the category is also missing, retries again, and so on. This gathers
 * every problem in a single pass and names the offending product, so it can all be fixed at
 * once.
 *
 * <p>It validates the <b>built submission</b> — the exact values that will go on the wire,
 * after the payload factory's fallbacks — so "what we validate is what we send." It is
 * deliberately conservative: it only asserts the rules QuikShipX has actually rejected on
 * (per-product category and HSN length, the pickup warehouse, and the core recipient
 * fields), because inventing extra rules would block orders QuikShipX would have accepted.
 *
 * <p>Pure: no Spring, no I/O. Returns problems rather than throwing, so the caller decides
 * how to surface them.
 */
public final class ShipmentSubmissionValidator {

    /** QuikShipX rejects several fields that are not "greater than 1 character". */
    private static final int MIN_LENGTH = 2;

    private ShipmentSubmissionValidator() {
        // Pure static helper.
    }

    /**
     * Every problem that would make QuikShipX reject this submission, in reading order.
     * An empty list means the submission is publishable as far as we can tell.
     */
    public static List<String> validate(ShipmentSubmission submission) {
        List<String> problems = new ArrayList<>();
        if (submission == null) {
            problems.add("The shipment could not be assembled.");
            return problems;
        }

        validateShipment(submission.shipmentDetails(), problems);
        validateCustomer(submission.customerDetails(), problems);
        validateProducts(submission.productDetails(), problems);
        validateCredentials(submission.shipperDetails(), problems);
        return problems;
    }

    private static void validateShipment(ShipmentSubmission.ShipmentDetails shipment,
                                         List<String> problems) {
        if (shipment == null) {
            problems.add("Shipment details are missing.");
            return;
        }
        if (blank(shipment.shipmentPickupWarehouseId())) {
            problems.add("Pickup warehouse id is not set (Settings \u2192 Shipment Defaults).");
        }
    }

    private static void validateCustomer(ShipmentSubmission.CustomerDetails customer,
                                         List<String> problems) {
        if (customer == null) {
            problems.add("Customer details are missing.");
            return;
        }
        if (blank(customer.customerFullName())) {
            problems.add("Customer name is empty.");
        }
        if (blank(customer.customerFullAddress())) {
            problems.add("Delivery address is empty.");
        }
        // QuikShipX ships within India: a pincode is six digits.
        String pincode = trim(customer.customerPincode());
        if (pincode.length() != 6 || !pincode.chars().allMatch(Character::isDigit)) {
            problems.add("Delivery pincode must be 6 digits (was \"" + pincode + "\").");
        }
        String phone = trim(customer.customerPhoneNumber());
        if (phone.length() < 10 || !phone.chars().allMatch(Character::isDigit)) {
            problems.add("Customer phone must be at least 10 digits.");
        }
    }

    private static void validateProducts(List<ShipmentSubmission.ProductDetail> products,
                                         List<String> problems) {
        if (products == null || products.isEmpty()) {
            problems.add("The order has no products.");
            return;
        }
        for (int i = 0; i < products.size(); i++) {
            ShipmentSubmission.ProductDetail product = products.get(i);
            // Name the product the way QuikShipX does — PID(n), 1-based — so the message
            // lines up with the raw rejection an admin might also see.
            String label = "Product " + pid(product, i);
            if (shorterThanMin(product.productCategory())) {
                problems.add(label + " has no category "
                        + "(set it on the product, or set a default in Shipment Defaults).");
            }
            if (shorterThanMin(product.productHsnCode())) {
                problems.add(label + " has no HSN code "
                        + "(set it on the product, or set a default in Shipment Defaults).");
            }
        }
    }

    private static void validateCredentials(ShipmentSubmission.ShipperDetails shipper,
                                            List<String> problems) {
        if (shipper == null
                || blank(shipper.clientCode()) || blank(shipper.userId()) || blank(shipper.userSecret())) {
            problems.add("QuikShipX credentials are not configured.");
        }
    }

    private static String pid(ShipmentSubmission.ProductDetail product, int index) {
        String name = product == null ? "" : trim(product.productName());
        String position = "PID(" + (index + 1) + ")";
        return name.isEmpty() ? position : position + " \"" + name + "\"";
    }

    private static boolean shorterThanMin(String value) {
        return trim(value).length() < MIN_LENGTH;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
