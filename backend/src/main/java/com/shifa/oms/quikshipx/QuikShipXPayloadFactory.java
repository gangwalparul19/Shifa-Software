package com.shifa.oms.quikshipx;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.product.Product;
import com.shifa.oms.quikshipx.QuikShipXModels.CreatePayload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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

        BigDecimal subtotal = subtotal(order);
        BigDecimal discount = order.getDiscountAmount() == null ? BigDecimal.ZERO : order.getDiscountAmount();
        BigDecimal cod = order.getCodAmount() == null ? BigDecimal.ZERO : order.getCodAmount();
        boolean isCod = cod.signum() > 0;

        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("shipment_package_type", properties.packageType());
        shipment.put("shipment_dead_weight_in_grams", properties.deadWeightGrams());
        shipment.put("shipment_length", properties.lengthCm());
        shipment.put("shipment_width", properties.widthCm());
        shipment.put("shipment_height", properties.heightCm());
        shipment.put("shipment_pickup_warehouse_id", nullToEmpty(properties.warehouseId()));
        shipment.put("shipment_shipping_mode", properties.shippingMode());
        shipment.put("shipment_pay_mode", isCod ? "1" : "2");
        shipment.put("order_amount", money(order.getTotalAmount()));
        shipment.put("cod_amount", isCod ? money(cod) : "0");
        shipment.put("commodity_amount", money(subtotal));
        shipment.put("shipping_amount", properties.shippingAmount());
        shipment.put("discount_amount", money(discount));
        shipment.put("discount_coupon_name", nullToEmpty(order.getCouponCode()));

        List<Map<String, Object>> products = new ArrayList<>();
        List<OrderLineItem> lines = order.getLineItems();
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
            p.put("product_discount", "0");
            p.put("product_quantity", String.valueOf(line.getQuantity()));
            products.add(p);
        }

        return new CreatePayload(order.getOrderCode(), customer, shipment, products);
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
