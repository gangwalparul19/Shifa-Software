package com.shifa.oms.integration.quikshipx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: shopify-quikshipx-order-sync, pre-flight shipment validation (Req 16.6).
 *
 * <p>The behaviour that matters: a publishable submission yields no problems, and an
 * unpublishable one lists <b>every</b> problem in a single pass — the whole reason the
 * validator exists is to stop the retry-by-retry loop where QuikShipX reveals one missing
 * field at a time.
 */
class ShipmentSubmissionValidatorTest {

    @Test
    void aCompleteSubmissionHasNoProblems() {
        assertThat(ShipmentSubmissionValidator.validate(valid())).isEmpty();
    }

    @Test
    void aBlankCategoryAndHsnAreBothReportedForTheRightProduct() {
        ShipmentSubmission submission = withProducts(
                product("Ashwagandha", "Immunity", "30049011"),
                product("Triphala", "", ""));

        List<String> problems = ShipmentSubmissionValidator.validate(submission);

        // Both gaps on PID(2) are reported at once, named by position and product.
        assertThat(problems).hasSize(2);
        assertThat(problems).anySatisfy(p -> assertThat(p)
                .contains("PID(2)").contains("Triphala").contains("category"));
        assertThat(problems).anySatisfy(p -> assertThat(p)
                .contains("PID(2)").contains("Triphala").contains("HSN"));
        // PID(1) is fine, so it is not mentioned.
        assertThat(problems).noneMatch(p -> p.contains("PID(1)"));
    }

    @Test
    void everyKindOfProblemIsGatheredInOnePass() {
        ShipmentSubmission broken = new ShipmentSubmission(
                new ShipmentSubmission.CustomerDetails(
                        "", "12345", "", "41", "SHIFA-1", "02 August 2026", "1", null, null, null),
                new ShipmentSubmission.ShipmentDetails(
                        "1", "500", "10", "10", "10", "", "1", "1", "500", "0", "500", "0", "0", null),
                List.of(product("Item", "", "")),
                new ShipmentSubmission.ShipperDetails("", "", ""));

        List<String> problems = ShipmentSubmissionValidator.validate(broken);

        // Warehouse, name, address, pincode, phone, product category, product HSN,
        // credentials — all surfaced together rather than one per network round-trip.
        assertThat(problems).anyMatch(p -> p.contains("Pickup warehouse"));
        assertThat(problems).anyMatch(p -> p.contains("Customer name"));
        assertThat(problems).anyMatch(p -> p.contains("Delivery address"));
        assertThat(problems).anyMatch(p -> p.contains("pincode"));
        assertThat(problems).anyMatch(p -> p.contains("phone"));
        assertThat(problems).anyMatch(p -> p.contains("category"));
        assertThat(problems).anyMatch(p -> p.contains("HSN"));
        assertThat(problems).anyMatch(p -> p.contains("credentials"));
    }

    @Test
    void anOrderWithNoProductsIsRejected() {
        assertThat(ShipmentSubmissionValidator.validate(withProducts()))
                .anyMatch(p -> p.contains("no products"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ShipmentSubmission.ProductDetail product(String name, String category, String hsn) {
        return new ShipmentSubmission.ProductDetail(
                name, category, "SKU-1", "12.00", hsn, "500", "0", "1");
    }

    private static ShipmentSubmission withProducts(ShipmentSubmission.ProductDetail... products) {
        return new ShipmentSubmission(
                valid().customerDetails(), valid().shipmentDetails(),
                List.of(products), valid().shipperDetails());
    }

    private static ShipmentSubmission valid() {
        return new ShipmentSubmission(
                new ShipmentSubmission.CustomerDetails(
                        "Asha Kumar", "9812345678", "12 MG Road, Pune, Maharashtra",
                        "411001", "SHIFA-SHR-1", "02 August 2026", "1",
                        "asha@example.com", null, null),
                new ShipmentSubmission.ShipmentDetails(
                        "1", "500", "10", "10", "10", "87", "1", "1",
                        "500", "0", "500", "0", "0", null),
                List.of(product("Ashwagandha", "Immunity", "30049011")),
                new ShipmentSubmission.ShipperDetails("CLIENT1", "USER1", "SECRET1"));
    }
}
