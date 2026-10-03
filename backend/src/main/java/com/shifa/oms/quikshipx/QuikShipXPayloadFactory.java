package com.shifa.oms.quikshipx;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.product.Product;
import com.shifa.oms.quikshipx.QuikShipXModels.CreatePayload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the QuikShipX create-order request body ({@code customer_details},
 * {@code shipment_details}, {@code product_details}) from a Shifa order, the
 * order's products (for SKU), and the admin-configured shipment defaults. Pure
 * apart from reading {@link QuikShipXProperties}, so the field mapping is easy to
 * reason about and adjust.
 *
 * <p>Field mapping decisions:
 * <ul>
 *   <li>{@code customer_order_id} = the Shifa order code (unique + stable, so a
 *       retry after an ambiguous timeout does not create a second shipment);</li>
 *   <li>{@code shipment_pay_mode} = COD ({@code 1}) when the order carries a COD
 *       amount, else PREPAID ({@code 2});</li>
 *   <li>{@code commodity_amount} = the gross subtotal (Σ line totals, pre-discount),
 *       {@code discount_amount} = the order discount, {@code order_amount} = the
 *       net payable total;</li>
 *   <li>weight, dimensions, package type, shipping mode, shipping amount, pickup
 *       warehouse, and product category come from the configured defaults.</li>
 * </ul>
 */
@Component
public class QuikShipXPayloadFactory {

    private static final DateTimeFormatter ORDER_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final QuikShipXProperties properties;

    public QuikShipXPayloadFactory(QuikShipXProperties properties) {
        this.properties = properties;
    }

    /**
     * Builds the create-order payload for an order.
     *
     * @param order       the order aggregate (with line items)
     * @param productsById the order's products keyed by id, for SKU lookup (a
     *                     missing product falls back to the order code + index)
     */
    public CreatePayload build(OrderEntity order, Map<Long, Product> productsById) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("customer_full_name", nullToEmpty(order.getCustomerName()));
        customer.put("customer_phone_number", nullToEmpty(order.getCustomerMobile()));
        customer.put("customer_full_address", fullAddress(order));
        customer.put("customer_pincode", nullToEmpty(order.getPostalCode()));
        customer.put("customer_order_id", order.getOrderCode());
        customer.put("customer_order_date",
                order.getCreatedAt() == null ? "" : order.getCreatedAt().format(ORDER_DATE));
        customer.put("customer_address_type", "1"); // Home.
        customer.put("customer_email_id", nullToEmpty(order.getCustomerEmail()));
        customer.put("customer_alternate_phone_number", nullToEmpty(order.getAlternateMobile()));
        customer.put("customer_landmark", "");

        List<OrderLineItem> lines = order.getLineItems();
        BigDecimal subtotal = subtotal(order).setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = order.getDiscountAmount() == null ? BigDecimal.ZERO : order.getDiscountAmount();
        discount = discount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal orderAmount = order.getTotalAmount() == null
                ? subtotal.subtract(discount) : order.getTotalAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal cod = order.getCodAmount() == null ? BigDecimal.ZERO : order.getCodAmount();
        boolean isCod = cod.signum() > 0;

        // QuikShipX validates the create-order strictly as
        //   Σ(product_amount × product_quantity − product_discount) == order_amount
        // per line (proven empirically from live request/response bodies — it does
        // NOT add shipping_amount nor subtract the order-level discount_amount).
        // Reconcile the product lines so that identity holds EXACTLY:
        //  • charged total < gross line subtotal (a Shopify checkout discount): the
        //    difference is apportioned across the lines as product_discount, summing
        //    to EXACTLY the gap (last line absorbs the rounding remainder);
        //  • charged total > gross line subtotal (Shopify added shipping/handling we
        //    don't itemise): the positive excess is added as one extra
        //    "Shipping & handling" product line so the products total ties out.
        // discount_amount / shipping_amount / commodity_amount below are cosmetic
        // (they don't participate in the check) but kept accurate for readability.
        BigDecimal delta = orderAmount.subtract(subtotal).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lineDiscountTotal = delta.signum() < 0 ? delta.negate() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal extraCharge = delta.signum() > 0 ? delta : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal[] lineDiscounts = apportionDiscount(lines, subtotal, lineDiscountTotal);

        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("shipment_package_type", properties.packageType());
        shipment.put("shipment_dead_weight_in_grams", properties.deadWeightGrams());
        shipment.put("shipment_length", properties.lengthCm());
        shipment.put("shipment_width", properties.widthCm());
        shipment.put("shipment_height", properties.heightCm());
        shipment.put("shipment_pickup_warehouse_id", nullToEmpty(properties.warehouseId()));
        shipment.put("shipment_shipping_mode", properties.shippingMode());
        shipment.put("shipment_pay_mode", isCod ? "1" : "2");
        shipment.put("order_amount", money(orderAmount));
        shipment.put("cod_amount", isCod ? money(cod) : "0");
        shipment.put("commodity_amount", money(subtotal.add(extraCharge)));
        shipment.put("shipping_amount", extraCharge.signum() > 0 ? money(extraCharge) : properties.shippingAmount());
        shipment.put("discount_amount", money(lineDiscountTotal));
        shipment.put("discount_coupon_name", nullToEmpty(order.getCouponCode()));

        List<Map<String, Object>> products = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            OrderLineItem line = lines.get(i);
            Product product = line.getProductId() == null ? null : productsById.get(line.getProductId());
            String sku = product != null && product.getSku() != null && !product.getSku().isBlank()
                    ? product.getSku()
                    : order.getOrderCode() + "-" + (i + 1);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("product_name", nullToEmpty(line.getProductName()));
            p.put("product_category", properties.productCategory());
            p.put("product_sku_code", sku);
            p.put("product_tax_rate", line.getGstRate() == null ? "0" : line.getGstRate().stripTrailingZeros().toPlainString());
            p.put("product_hsn_code", blankTo(line.getHsnCode(), "hsn"));
            p.put("product_amount", money(line.getRate()));
            p.put("product_discount", money(lineDiscounts[i]));
            p.put("product_quantity", String.valueOf(line.getQuantity()));
            products.add(p);
        }
        // Extra charge (e.g. Shopify shipping) as its own line so the products total
        // equals order_amount exactly (qty 1, no discount).
        if (extraCharge.signum() > 0) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("product_name", "Shipping & handling");
            p.put("product_category", properties.productCategory());
            p.put("product_sku_code", order.getOrderCode() + "-SHIP");
            p.put("product_tax_rate", "0");
            p.put("product_hsn_code", "hsn");
            p.put("product_amount", money(extraCharge));
            p.put("product_discount", "0");
            p.put("product_quantity", "1");
            products.add(p);
        }

        // QuikShipX compares Σ(product_amount*qty − product_discount) with
        // order_amount using FLOATING-POINT equality (proven: 999 + 1099.99 +
        // 1099.99 − 100 = 3098.9799999999996 ≠ 3098.98 → rejected, while sums that
        // happen to be float-exact pass). If the itemised lines would not sum to the
        // exact same double, send one consolidated line instead — a single value
        // always equals itself, so the check can never fail.
        if (!floatSumMatches(products, money(orderAmount))) {
            return buildConsolidated(order, customer, shipment, orderAmount);
        }
        return new CreatePayload(order.getOrderCode(), customer, shipment, products);
    }

    /**
     * The same order as a single consolidated product line (names joined, qty 1,
     * amount == order_amount, no discount). Used when the itemised lines would not
     * pass QuikShipX's floating-point amount check, and as a fallback when QuikShipX
     * rejects an itemised payload with "Calculated Products and Order Amount Not
     * Matched". Customer and shipment details are identical to {@link #build}.
     */
    public CreatePayload buildConsolidated(OrderEntity order, Map<Long, Product> productsById) {
        CreatePayload itemised = build(order, productsById);
        BigDecimal orderAmount = order.getTotalAmount() == null
                ? new BigDecimal(String.valueOf(itemised.shipmentDetails().get("order_amount")))
                : order.getTotalAmount().setScale(2, RoundingMode.HALF_UP);
        return buildConsolidated(order, itemised.customerDetails(), itemised.shipmentDetails(), orderAmount);
    }

    private CreatePayload buildConsolidated(OrderEntity order, Map<String, Object> customer,
                                            Map<String, Object> shipment, BigDecimal orderAmount) {
        StringBuilder names = new StringBuilder();
        for (OrderLineItem line : order.getLineItems()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(nullToEmpty(line.getProductName())).append(" x").append(line.getQuantity());
        }
        String name = names.length() == 0 ? order.getOrderCode() : names.toString();
        if (name.length() > 250) {
            name = name.substring(0, 247) + "...";
        }
        Map<String, Object> consolidatedShipment = new LinkedHashMap<>(shipment);
        consolidatedShipment.put("commodity_amount", money(orderAmount));
        consolidatedShipment.put("discount_amount", "0");
        consolidatedShipment.put("shipping_amount", properties.shippingAmount());

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("product_name", name);
        p.put("product_category", properties.productCategory());
        p.put("product_sku_code", order.getOrderCode() + "-ALL");
        p.put("product_tax_rate", "0");
        p.put("product_hsn_code", "hsn");
        p.put("product_amount", money(orderAmount));
        p.put("product_discount", "0");
        p.put("product_quantity", "1");
        List<Map<String, Object>> products = new ArrayList<>();
        products.add(p);
        return new CreatePayload(order.getOrderCode(), customer, consolidatedShipment, products);
    }

    /**
     * Simulates QuikShipX's check in IEEE-754 double arithmetic (left-to-right
     * accumulation of amount*qty − discount per line) and reports whether it
     * equals the order amount exactly.
     */
    static boolean floatSumMatches(List<Map<String, Object>> products, String orderAmount) {
        double sum = 0d;
        for (Map<String, Object> p : products) {
            double amount = Double.parseDouble(String.valueOf(p.get("product_amount")));
            double qty = Double.parseDouble(String.valueOf(p.get("product_quantity")));
            double disc = Double.parseDouble(String.valueOf(p.get("product_discount")));
            sum += amount * qty - disc;
        }
        return sum == Double.parseDouble(orderAmount);
    }

    private static String fullAddress(OrderEntity order) {
        StringBuilder sb = new StringBuilder();
        append(sb, order.getAddressLine());
        append(sb, order.getCity());
        append(sb, order.getState());
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part.trim());
        }
    }

    private static BigDecimal subtotal(OrderEntity order) {
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderLineItem line : order.getLineItems()) {
            if (line.getLineTotal() != null) {
                sum = sum.add(line.getLineTotal());
            }
        }
        return sum;
    }

    /**
     * Splits {@code discount} across the lines proportional to each line's total,
     * rounded to 2dp so the shares sum EXACTLY to the discount (largest-remainder:
     * the rounding remainder is handed to the lines with the largest fractional
     * part, one paisa each). Returns a per-line array aligned to {@code lines}. A
     * zero/absent discount (or zero subtotal) yields all zeros. Each share never
     * exceeds its own line total.
     */
    static BigDecimal[] apportionDiscount(List<OrderLineItem> lines, BigDecimal subtotal, BigDecimal discount) {
        int n = lines.size();
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal[] shares = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            shares[i] = zero;
        }
        if (discount == null || discount.signum() <= 0 || subtotal == null || subtotal.signum() <= 0 || n == 0) {
            return shares;
        }
        BigDecimal target = discount.setScale(2, RoundingMode.HALF_UP);
        // Round each line's proportional share to 2dp; the LAST line takes whatever
        // is left so the shares sum to EXACTLY the discount (no dropped/created
        // paisa). Each share is clamped to its own line total.
        BigDecimal allocated = zero;
        int lastIdx = n - 1;
        for (int i = 0; i < n; i++) {
            BigDecimal lineTotal = lines.get(i).getLineTotal() == null ? zero : lines.get(i).getLineTotal();
            BigDecimal share;
            if (i == lastIdx) {
                share = target.subtract(allocated); // exact remainder
            } else {
                share = target.multiply(lineTotal).divide(subtotal, 2, RoundingMode.HALF_UP);
            }
            if (share.signum() < 0) {
                share = zero;
            }
            if (share.compareTo(lineTotal) > 0) {
                share = lineTotal; // never exceed the line's own total
            }
            shares[i] = share;
            allocated = allocated.add(share);
        }
        // If clamping the last line left a residual (only possible when a line total
        // was smaller than its computed share), sweep it onto any line with headroom.
        BigDecimal residual = target.subtract(allocated);
        BigDecimal penny = new BigDecimal("0.01");
        while (residual.compareTo(penny) >= 0) {
            boolean placed = false;
            for (int i = 0; i < n; i++) {
                BigDecimal lineTotal = lines.get(i).getLineTotal() == null ? zero : lines.get(i).getLineTotal();
                if (shares[i].add(penny).compareTo(lineTotal) <= 0) {
                    shares[i] = shares[i].add(penny);
                    residual = residual.subtract(penny);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                break;
            }
        }
        return shares;
    }

    /** Renders money as a plain decimal string (no scientific notation, no currency symbol). */
    private static String money(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
