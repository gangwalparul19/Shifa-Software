package com.shifa.oms.integration.quikshipx;

import java.util.List;

/**
 * The QuikShipX create-order request body, as a pure model
 * (contract: {@code docs/QUIKSHIPX-API-V1.md}).
 *
 * <p>Mirrors the documented four-section structure —
 * {@code customer_details}, {@code shipment_details}, {@code product_details} and
 * {@code shipper_details} — with every field already a {@link String}, because that
 * is what QuikShipX expects on the wire for amounts, dimensions and quantities alike.
 * Formatting happens in {@link QuikShipXValueFormat} before a submission is built, so
 * this record carries no formatting logic and can be compared field-for-field in a
 * round-trip property test.
 *
 * <p>{@link ShipperDetails} holds the credentials, because QuikShipX authenticates in
 * the body rather than in a header. That means a submission is sensitive: never log one
 * verbatim, and {@link #redacted()} exists for when a submission must appear in a
 * failure reason or an audit trail.
 */
public record ShipmentSubmission(
        CustomerDetails customerDetails,
        ShipmentDetails shipmentDetails,
        List<ProductDetail> productDetails,
        ShipperDetails shipperDetails) {

    public ShipmentSubmission {
        productDetails = productDetails == null ? List.of() : List.copyOf(productDetails);
    }

    /** The shipment recipient ({@code customer_details}). */
    public record CustomerDetails(
            String customerFullName,
            String customerPhoneNumber,
            String customerFullAddress,
            String customerPincode,
            String customerOrderId,
            String customerOrderDate,
            String customerAddressType,
            String customerEmailId,
            String customerAlternatePhoneNumber,
            String customerLandmark) {
    }

    /** Parcel and money attributes ({@code shipment_details}). */
    public record ShipmentDetails(
            String shipmentPackageType,
            String shipmentDeadWeightInGrams,
            String shipmentLength,
            String shipmentWidth,
            String shipmentHeight,
            String shipmentPickupWarehouseId,
            String shipmentShippingMode,
            String shipmentPayMode,
            String orderAmount,
            String codAmount,
            String commodityAmount,
            String shippingAmount,
            String discountAmount,
            String discountCouponName) {
    }

    /** One ordered item ({@code product_details[]}). */
    public record ProductDetail(
            String productName,
            String productCategory,
            String productSkuCode,
            String productTaxRate,
            String productHsnCode,
            String productAmount,
            String productDiscount,
            String productQuantity) {
    }

    /**
     * Body-carried credentials ({@code shipper_details}). QuikShipX has no
     * authorization header, so these travel with every submission.
     */
    public record ShipperDetails(
            String clientCode,
            String userId,
            String userSecret) {
    }

    /** The order reference we sent, which is also the correlation key. */
    public String orderReference() {
        return customerDetails == null ? null : customerDetails.customerOrderId();
    }

    /**
     * A copy with the credentials masked, safe to put in a log line, an audit entry or
     * a stored failure reason. Secrets in an {@code integration_events} row would be a
     * durable leak, so nothing should ever persist a raw submission.
     */
    public ShipmentSubmission redacted() {
        return new ShipmentSubmission(customerDetails, shipmentDetails, productDetails,
                new ShipperDetails(mask(shipperDetails == null ? null : shipperDetails.clientCode()),
                        mask(shipperDetails == null ? null : shipperDetails.userId()),
                        mask(shipperDetails == null ? null : shipperDetails.userSecret())));
    }

    private static String mask(String value) {
        return value == null || value.isEmpty() ? "" : "***";
    }
}
