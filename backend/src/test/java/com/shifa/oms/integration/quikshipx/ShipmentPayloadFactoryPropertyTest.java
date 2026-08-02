package com.shifa.oms.integration.quikshipx;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.settings.AppSettings;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: shopify-quikshipx-order-sync, Property 36 (whole-body half) and Property 37
 * (default application and weight rollup).
 *
 * <p>For any order and any valid shipment defaults, the create-order body carries the
 * configured defaults, the channel-prefixed order reference, a pay mode consistent with
 * the amount to collect, and a parcel weight that is the per-product rollup when any
 * product carries a weight and the settings default otherwise.
 *
 * <p>Validates: Requirements 5.2, 5.3, 5.13, 8.1, 8.11, 8.12, 16.5, 16.7
 */
class ShipmentPayloadFactoryPropertyTest {

    private static final QuikShipXProperties PROPERTIES = new QuikShipXProperties(
            true, "MOCK", "https://head.quikshipx.com", "CLIENT", "USER", "SECRET",
            "TEST", "SHIFA-", false, null, null, null, null, null, null);

    // --- Pay mode / COD consistency ----------------------------------------

    @Property(tries = 500)
    void payModeAndCodAmountFollowTheAmountToCollect(
            @ForAll @LongRange(min = 0, max = 50_000) long codRupees) {

        BigDecimal cod = BigDecimal.valueOf(codRupees);
        OrderEntity order = order(cod, BigDecimal.valueOf(1000), BigDecimal.ZERO);

        ShipmentSubmission body = ShipmentPayloadFactory.build(
                order, settings(), Map.of(), PROPERTIES);

        if (codRupees > 0) {
            assertThat(body.shipmentDetails().shipmentPayMode()).isEqualTo("1");
            assertThat(new BigDecimal(body.shipmentDetails().codAmount()))
                    .isEqualByComparingTo(cod);
        } else {
            assertThat(body.shipmentDetails().shipmentPayMode()).isEqualTo("2");
            // A non-zero COD on a prepaid parcel would collect money twice.
            assertThat(body.shipmentDetails().codAmount()).isEqualTo("0");
        }
    }

    // --- Weight rollup ------------------------------------------------------

    @Property(tries = 500)
    void weightIsThePerProductRollupWhenProductsCarryWeights(
            @ForAll @IntRange(min = 1, max = 900) int perUnitGrams,
            @ForAll @IntRange(min = 1, max = 6) int quantity) {

        Product product = product(1L, perUnitGrams);
        OrderEntity order = order(BigDecimal.ZERO, BigDecimal.valueOf(500), BigDecimal.ZERO);
        order.addLineItem(line(1L, quantity));

        ShipmentSubmission body = ShipmentPayloadFactory.build(
                order, settings(), Map.of(1L, product), PROPERTIES);

        assertThat(body.shipmentDetails().shipmentDeadWeightInGrams())
                .isEqualTo(String.valueOf(perUnitGrams * quantity));
    }

    @Property(tries = 300)
    void weightFallsBackToTheSettingsDefaultWhenNoProductCarriesOne(
            @ForAll @IntRange(min = 1, max = 4) int lines,
            @ForAll @IntRange(min = 1, max = 50_000) int defaultWeight) {

        OrderEntity order = order(BigDecimal.ZERO, BigDecimal.valueOf(500), BigDecimal.ZERO);
        Map<Long, Product> products = new HashMap<>();
        for (long i = 1; i <= lines; i++) {
            order.addLineItem(line(i, 1));
            products.put(i, product(i, null)); // no weight recorded
        }

        AppSettings settings = settings();
        settings.setShipDeadWeightGrams(defaultWeight);

        ShipmentSubmission body = ShipmentPayloadFactory.build(order, settings, products, PROPERTIES);

        // Sending a wildly wrong weight risks the courier re-weighing and re-rating.
        assertThat(body.shipmentDetails().shipmentDeadWeightInGrams())
                .isEqualTo(String.valueOf(defaultWeight));
    }

    @Test
    void aLineWithNoProductLinkStillProducesAnItem() {
        OrderEntity order = order(BigDecimal.ZERO, BigDecimal.valueOf(500), BigDecimal.ZERO);
        // A Shopify line whose SKU matched nothing has no product id.
        order.addLineItem(new OrderLineItem(null, "Unmatched item", null, null, 2,
                new BigDecimal("250.00"), new BigDecimal("500.00")));

        ShipmentSubmission body = ShipmentPayloadFactory.build(
                order, settings(), Map.of(), PROPERTIES);

        assertThat(body.productDetails()).hasSize(1);
        ShipmentSubmission.ProductDetail item = body.productDetails().get(0);
        assertThat(item.productName()).isEqualTo("Unmatched item");
        assertThat(item.productQuantity()).isEqualTo("2");
        // Falls back to the configured category rather than sending an empty one.
        assertThat(item.productCategory()).isEqualTo("Herbal");
        // Never null: a JSON null where QuikShipX expects a string would be rejected.
        assertThat(item.productHsnCode()).isNotNull();
        assertThat(item.productSkuCode()).isNotNull();
    }

    // --- Defaults and reference --------------------------------------------

    @Test
    void theConfiguredDefaultsAndReferenceAreCarriedIntoTheBody() {
        OrderEntity order = order(BigDecimal.ZERO, BigDecimal.valueOf(999), BigDecimal.ZERO);
        order.addLineItem(line(1L, 1));

        AppSettings settings = settings();
        settings.setShipPickupWarehouseId("65");
        settings.setShipPackageType("2");
        settings.setShipShippingMode("2");
        settings.setShipLengthCm(11);
        settings.setShipWidthCm(22);
        settings.setShipHeightCm(33);
        settings.setShipShippingAmount(new BigDecimal("60.00"));

        ShipmentSubmission body = ShipmentPayloadFactory.build(
                order, settings, Map.of(1L, product(1L, null)), PROPERTIES);

        ShipmentSubmission.ShipmentDetails s = body.shipmentDetails();
        assertThat(s.shipmentPickupWarehouseId()).isEqualTo("65");
        assertThat(s.shipmentPackageType()).isEqualTo("2");
        assertThat(s.shipmentShippingMode()).isEqualTo("2");
        assertThat(s.shipmentLength()).isEqualTo("11");
        assertThat(s.shipmentWidth()).isEqualTo("22");
        assertThat(s.shipmentHeight()).isEqualTo("33");
        assertThat(s.shippingAmount()).isEqualTo("60.00");

        // The channel rides in customer_order_id, since the API has no channel field.
        assertThat(body.customerDetails().customerOrderId()).isEqualTo("SHIFA-SHR-1001");
        assertThat(body.orderReference()).isEqualTo("SHIFA-SHR-1001");
        // Credentials travel in the body, not a header.
        assertThat(body.shipperDetails().userSecret()).isEqualTo("SECRET");
    }

    @Test
    void theAddressIsComposedAndThePincodeKeptSeparate() {
        OrderEntity order = order(BigDecimal.ZERO, BigDecimal.valueOf(999), BigDecimal.ZERO);

        ShipmentSubmission body = ShipmentPayloadFactory.build(
                order, settings(), Map.of(), PROPERTIES);

        assertThat(body.customerDetails().customerFullAddress())
                .isEqualTo("12 MG Road, Pune, Maharashtra");
        // Duplicating the pincode into the address risks the courier reading it twice.
        assertThat(body.customerDetails().customerFullAddress()).doesNotContain("411001");
        assertThat(body.customerDetails().customerPincode()).isEqualTo("411001");
    }

    @Test
    void commodityAmountAddsBackTheDiscountSoTheInsuredValueIsPreDiscount() {
        // totalAmount is already net of the discount, so sending it as commodity_amount
        // would under-insure the parcel against loss.
        OrderEntity order = order(BigDecimal.ZERO, new BigDecimal("900.00"), new BigDecimal("100.00"));

        ShipmentSubmission body = ShipmentPayloadFactory.build(
                order, settings(), Map.of(), PROPERTIES);

        assertThat(body.shipmentDetails().orderAmount()).isEqualTo("900.00");
        assertThat(body.shipmentDetails().commodityAmount()).isEqualTo("1000.00");
        assertThat(body.shipmentDetails().discountAmount()).isEqualTo("100.00");
    }

    @Test
    void theBodySerializesWithEveryValueAsAString() {
        OrderEntity order = order(BigDecimal.ZERO, BigDecimal.valueOf(999), BigDecimal.ZERO);
        order.addLineItem(line(1L, 2));

        ShipmentSubmission body = ShipmentPayloadFactory.build(
                order, settings(), Map.of(1L, product(1L, 250)), PROPERTIES);
        String json = QuikShipXSubmissionCodec.serialize(body);

        // Round-trips through the codec, and the date is QuikShipX's human form.
        assertThat(QuikShipXSubmissionCodec.parse(json)).isEqualTo(body);
        assertThat(json).contains("\"customer_order_date\":\"14 March 2026\"");
        assertThat(json).contains("\"shipment_dead_weight_in_grams\":\"500\"");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static AppSettings settings() {
        AppSettings settings = AppSettings.defaults();
        settings.setShipPickupWarehouseId("65");
        settings.setShipDefaultCategory("Herbal");
        return settings;
    }

    private static OrderEntity order(BigDecimal cod, BigDecimal total, BigDecimal discount) {
        OrderEntity order = new OrderEntity("SHR-1001", OrderSource.SHIFA_ADMIN, 7L,
                "Asha Kumar", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(total, total.subtract(cod), cod, cod,
                com.shifa.oms.order.domain.PaymentStatus.COD);
        order.applyDiscount(null, discount);
        // createdAt is DB-filled, so a test must set it to exercise the date format.
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.of(2026, 3, 14, 10, 30));
        ReflectionTestUtils.setField(order, "id", 1001L);
        return order;
    }

    private static OrderLineItem line(long productId, int quantity) {
        return new OrderLineItem(productId, "Product " + productId, "3004",
                new BigDecimal("5.00"), quantity,
                new BigDecimal("199.00"), new BigDecimal("199.00").multiply(BigDecimal.valueOf(quantity)));
    }

    private static Product product(long id, Integer deadWeightGrams) {
        Product product = new Product("SKU-" + id, "Product " + id, "desc",
                new BigDecimal("299.00"), new BigDecimal("199.00"), ProductVisibility.PUBLISHED);
        ReflectionTestUtils.setField(product, "id", id);
        product.setDeadWeightGrams(deadWeightGrams);
        return product;
    }
}
