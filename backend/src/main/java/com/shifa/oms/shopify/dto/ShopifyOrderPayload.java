package com.shifa.oms.shopify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * The subset of a Shopify {@code orders/create} webhook payload that we import
 * into an OMS order. Unknown fields are ignored so Shopify can evolve its schema
 * without breaking parsing.
 *
 * <p>Shopify sends money as strings (e.g. {@code "1299.00"}); Jackson binds those
 * to {@link BigDecimal}. The customer/shipping blocks are optional depending on
 * the store's checkout configuration, so every nested type is null-tolerant and
 * the import service falls back gracefully.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShopifyOrderPayload(
        Long id,
        @JsonProperty("order_number") Long orderNumber,
        String name,
        String email,
        String phone,
        String note,
        String currency,
        @JsonProperty("total_price") BigDecimal totalPrice,
        @JsonProperty("total_discounts") BigDecimal totalDiscounts,
        @JsonProperty("total_outstanding") BigDecimal totalOutstanding,
        @JsonProperty("financial_status") String financialStatus,
        @JsonProperty("line_items") List<LineItem> lineItems,
        Customer customer,
        @JsonProperty("shipping_address") Address shippingAddress,
        @JsonProperty("billing_address") Address billingAddress) {

    /** A Shopify order line. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineItem(
            String sku,
            String title,
            String name,
            Integer quantity,
            BigDecimal price) {
    }

    /** The Shopify customer block (may be absent for guest checkout). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            String email,
            String phone) {
    }

    /** A Shopify address block (shipping or billing). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(
            String name,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            String phone,
            String address1,
            String address2,
            String city,
            String province,
            String zip,
            String country) {
    }
}
