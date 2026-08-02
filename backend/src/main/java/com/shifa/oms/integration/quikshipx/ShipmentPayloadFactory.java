package com.shifa.oms.integration.quikshipx;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.product.Product;
import com.shifa.oms.settings.AppSettings;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Builds the QuikShipX create-order body from a Shifa order, the admin-managed
 * shipment defaults and the products on the order
 * (contract: {@code docs/QUIKSHIPX-API-V1.md}; Req 16.5, 16.7).
 *
 * <p>This is where the mismatch between the two systems is reconciled. QuikShipX needs
 * parcel logistics Shifa has never captured per order — pickup warehouse, package type,
 * shipping mode, weight, dimensions, shipping amount, product category — so those come
 * from {@link AppSettings}, optionally refined per product.
 *
 * <p>Pure: everything it needs is passed in, so the whole body is property-testable
 * without a database.
 */
public final class ShipmentPayloadFactory {

    private ShipmentPayloadFactory() {
        // Pure static helper.
    }

    /**
     * @param order        the order to publish
     * @param settings     the admin-managed shipment defaults
     * @param productsById the products referenced by the order's lines, for category,
     *                     HSN, tax rate and per-product weight; a missing entry falls
     *                     back to the line's own snapshot and the settings defaults
     * @param properties   supplies the credentials and the channel prefix
     */
    public static ShipmentSubmission build(OrderEntity order, AppSettings settings,
                                           Map<Long, Product> productsById,
                                           QuikShipXProperties properties) {
        String reference = OrderReference.of(properties.orderReferencePrefix(), order.getOrderCode());
        BigDecimal toCollect = amountToCollect(order);

        return new ShipmentSubmission(
                new ShipmentSubmission.CustomerDetails(
                        QuikShipXValueFormat.text(order.getCustomerName()),
                        QuikShipXValueFormat.text(order.getCustomerMobile()),
                        QuikShipXValueFormat.fullAddress(
                                order.getAddressLine(), order.getCity(), order.getState()),
                        QuikShipXValueFormat.text(order.getPostalCode()),
                        reference,
                        QuikShipXValueFormat.date(order.getCreatedAt()),
                        // Shifa does not capture home vs office, and every order to date is
                        // a residential delivery, so Home is the honest default.
                        QuikShipXValueFormat.ADDRESS_TYPE_HOME,
                        QuikShipXValueFormat.text(order.getCustomerEmail()),
                        QuikShipXValueFormat.text(order.getAlternateMobile()),
                        // Shifa has no landmark field; sent empty, as the contract allows.
                        ""),
                new ShipmentSubmission.ShipmentDetails(
                        QuikShipXValueFormat.text(settings.getShipPackageType()),
                        QuikShipXValueFormat.integer(deadWeightGrams(order, settings, productsById)),
                        QuikShipXValueFormat.integer(settings.getShipLengthCm()),
                        QuikShipXValueFormat.integer(settings.getShipWidthCm()),
                        QuikShipXValueFormat.integer(settings.getShipHeightCm()),
                        QuikShipXValueFormat.text(settings.getShipPickupWarehouseId()),
                        QuikShipXValueFormat.text(settings.getShipShippingMode()),
                        QuikShipXValueFormat.payMode(toCollect),
                        QuikShipXValueFormat.money(order.getTotalAmount()),
                        QuikShipXValueFormat.codAmount(toCollect),
                        // commodity_amount is the pre-discount value QuikShipX refunds if the
                        // parcel is lost, so it must NOT be the discounted total.
                        QuikShipXValueFormat.money(commodityAmount(order)),
                        QuikShipXValueFormat.money(settings.getShipShippingAmount()),
                        QuikShipXValueFormat.money(order.getDiscountAmount()),
                        QuikShipXValueFormat.text(order.getCouponCode())),
                products(order, settings, productsById),
                new ShipmentSubmission.ShipperDetails(
                        QuikShipXValueFormat.text(properties.clientCode()),
                        QuikShipXValueFormat.text(properties.userId()),
                        QuikShipXValueFormat.text(properties.userSecret())));
    }

    /**
     * What the courier must collect on delivery. Uses the order's COD amount, which the
     * payment calculator already derived from total minus received.
     */
    private static BigDecimal amountToCollect(OrderEntity order) {
        BigDecimal cod = order.getCodAmount();
        return cod == null ? BigDecimal.ZERO : cod;
    }

    /**
     * The insurable value: the order total with any discount added back, since
     * {@code totalAmount} already has the discount applied.
     */
    private static BigDecimal commodityAmount(OrderEntity order) {
        BigDecimal total = order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount();
        BigDecimal discount = order.getDiscountAmount() == null ? BigDecimal.ZERO : order.getDiscountAmount();
        return total.add(discount);
    }

    /**
     * Parcel weight: the sum over lines of the product's own weight times the line
     * quantity (Req 16.7). Falls back to the settings default when no product on the
     * order carries a weight, because sending a wildly wrong weight risks the courier
     * re-weighing and re-rating the shipment.
     */
    static int deadWeightGrams(OrderEntity order, AppSettings settings, Map<Long, Product> productsById) {
        int total = 0;
        boolean anyProductWeight = false;
        for (OrderLineItem line : order.getLineItems()) {
            Product product = product(line, productsById);
            Integer perUnit = product == null ? null : product.getDeadWeightGrams();
            if (perUnit != null && perUnit > 0) {
                anyProductWeight = true;
                total += perUnit * Math.max(1, line.getQuantity());
            }
        }
        if (!anyProductWeight || total <= 0) {
            return settings.getShipDeadWeightGrams();
        }
        return total;
    }

    private static List<ShipmentSubmission.ProductDetail> products(
            OrderEntity order, AppSettings settings, Map<Long, Product> productsById) {

        return order.getLineItems().stream()
                .map(line -> {
                    Product product = product(line, productsById);
                    return new ShipmentSubmission.ProductDetail(
                            QuikShipXValueFormat.text(line.getProductName()),
                            category(product, settings),
                            QuikShipXValueFormat.text(product == null ? null : product.getSku()),
                            // The line's tax rate and HSN are snapshots taken at order time,
                            // so a later product edit cannot rewrite a historic shipment.
                            QuikShipXValueFormat.money(line.getGstRate()),
                            hsn(line, product, settings),
                            QuikShipXValueFormat.money(line.getRate()),
                            // Discounts are applied at order level, not per line.
                            "0",
                            QuikShipXValueFormat.integer(line.getQuantity()));
                })
                .toList();
    }

    /**
     * The line's HSN code, falling back to the product's current HSN, then to the settings
     * default HSN (V50).
     *
     * <p>QuikShipX rejects an HSN shorter than two characters, and many Shifa products are
     * not HSN-coded, so a blank line snapshot would fail the whole create-order. The line's
     * own snapshot still wins when present, so a historic shipment is never rewritten; the
     * fallbacks only fill a gap the order left empty. When nothing is configured the value
     * is sent empty, and QuikShipX's rejection is surfaced verbatim to the admin.
     */
    private static String hsn(OrderLineItem line, Product product, AppSettings settings) {
        String fromLine = trimToNull(line.getHsnCode());
        if (fromLine != null) {
            return QuikShipXValueFormat.text(fromLine);
        }
        String fromProduct = product == null ? null : trimToNull(product.getHsnCode());
        if (fromProduct != null) {
            return QuikShipXValueFormat.text(fromProduct);
        }
        return QuikShipXValueFormat.text(settings.getShipDefaultHsn());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Last-resort product category when a product is uncategorised and no default is
     * configured. QuikShipX rejects a category shorter than two characters, so this
     * guarantees a non-blank value is always sent rather than failing the whole order.
     */
    static final String DEFAULT_CATEGORY = "General";

    /**
     * The product's category, then the settings fallback (Req 16.5), then a safe constant.
     * QuikShipX requires a category and many Shifa products are uncategorised, so the value
     * is never allowed to be blank.
     */
    private static String category(Product product, AppSettings settings) {
        if (product != null && product.getCategory() != null
                && product.getCategory().getName() != null
                && !product.getCategory().getName().isBlank()) {
            return product.getCategory().getName().trim();
        }
        String settingsDefault = settings == null ? null : settings.getShipDefaultCategory();
        if (settingsDefault != null && !settingsDefault.isBlank()) {
            return settingsDefault.trim();
        }
        return DEFAULT_CATEGORY;
    }

    private static Product product(OrderLineItem line, Map<Long, Product> productsById) {
        Long productId = line.getProductId();
        return productId == null || productsById == null ? null : productsById.get(productId);
    }
}
