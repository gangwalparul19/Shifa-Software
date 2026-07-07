package com.shifa.oms.invoice;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.settings.AppSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example-based unit tests for {@link InvoiceContentBuilder}, asserting on the
 * render-agnostic {@link InvoiceContent} model (no PDF bytes parsed):
 * <ul>
 *   <li>invoice identity mirrors the order code;</li>
 *   <li>the bill-to customer block and priced line items are complete;</li>
 *   <li>totals mirror the order amounts;</li>
 *   <li>the COD / amount-due-on-delivery line is present for COD and
 *       Partially_Paid orders and omitted for Fully_Paid orders.</li>
 * </ul>
 */
class InvoiceContentBuilderTest {

    private final InvoiceContentBuilder builder = new InvoiceContentBuilder();

    @Test
    void invoiceIdentityAndCustomerBlockAreComplete() {
        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));

        InvoiceContent content = builder.build(order);

        assertThat(content.invoiceNumber()).isEqualTo("SHR-000123");
        assertThat(content.customerName()).isEqualTo("Asha");
        assertThat(content.customerMobile()).isEqualTo("9812345678");
        assertThat(content.fullAddress()).isEqualTo("12 MG Road, Pune, Maharashtra, 411001");
        assertThat(content.orderSource()).isEqualTo("Salesperson");
    }

    @Test
    void lineItemsArePricedAndNumbered() {
        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));

        InvoiceContent content = builder.build(order);

        assertThat(content.lineItems()).hasSize(1);
        InvoiceContent.InvoiceLineItem line = content.lineItems().get(0);
        assertThat(line.position()).isEqualTo(1);
        assertThat(line.productName()).isEqualTo("Neem Capsules");
        assertThat(line.quantity()).isEqualTo(2);
        assertThat(line.rate()).isEqualByComparingTo("120.00");
        assertThat(line.amount()).isEqualByComparingTo("240.00");
    }

    @Test
    void totalsMirrorTheOrderAmounts() {
        OrderEntity order = order(PaymentStatus.PARTIALLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("140.00"),
                new BigDecimal("100.00"), new BigDecimal("100.00"));

        InvoiceContent content = builder.build(order);

        assertThat(content.subtotal()).isEqualByComparingTo("240.00");
        assertThat(content.amountReceived()).isEqualByComparingTo("140.00");
        assertThat(content.balanceDue()).isEqualByComparingTo("100.00");
    }

    @Test
    void codLineShownForCodOrder() {
        OrderEntity order = order(PaymentStatus.COD,
                new BigDecimal("240.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("240.00"), new BigDecimal("240.00"));

        InvoiceContent content = builder.build(order);

        assertThat(content.codApplicable()).isTrue();
        assertThat(content.amountDueOnDelivery()).isEqualByComparingTo("240.00");
    }

    @Test
    void codLineShownForPartiallyPaidOrder() {
        OrderEntity order = order(PaymentStatus.PARTIALLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("140.00"),
                new BigDecimal("100.00"), new BigDecimal("100.00"));

        InvoiceContent content = builder.build(order);

        assertThat(content.codApplicable()).isTrue();
        assertThat(content.amountDueOnDelivery()).isEqualByComparingTo("100.00");
    }

    @Test
    void codLineOmittedForFullyPaidOrder() {
        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));

        InvoiceContent content = builder.build(order);

        assertThat(content.codApplicable()).isFalse();
        assertThat(content.amountDueOnDelivery()).isNull();
    }

    // --- Coupon discount (Phase D) ------------------------------------------

    @Test
    void discountRowReflectsCouponAndNetTotal() {
        // Gross subtotal 240 (single line), coupon SAVE40 gives 40 off -> net 200.
        OrderEntity order = order(PaymentStatus.COD,
                new BigDecimal("200.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("200.00"), new BigDecimal("200.00"));
        order.applyDiscount("SAVE40", new BigDecimal("40.00"));

        InvoiceContent content = builder.build(order);

        assertThat(content.hasDiscount()).isTrue();
        assertThat(content.couponCode()).isEqualTo("SAVE40");
        assertThat(content.subtotal()).isEqualByComparingTo("240.00");
        assertThat(content.discountAmount()).isEqualByComparingTo("40.00");
        assertThat(content.netTotal()).isEqualByComparingTo("200.00");
        // COD reflects the discounted net total.
        assertThat(content.amountDueOnDelivery()).isEqualByComparingTo("200.00");
    }

    @Test
    void noDiscountLeavesDiscountFieldsZeroAndSubtotalEqualsNet() {
        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));

        InvoiceContent content = builder.build(order);

        assertThat(content.hasDiscount()).isFalse();
        assertThat(content.couponCode()).isNull();
        assertThat(content.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(content.subtotal()).isEqualByComparingTo("240.00");
        assertThat(content.netTotal()).isEqualByComparingTo("240.00");
    }

    // --- GST tax invoice ----------------------------------------------------

    @Test
    void gstDisabledProducesPlainInvoiceWithNoTaxBlock() {
        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));

        // build(order) and build(order, disabledSettings, hsn) both produce a plain invoice.
        assertThat(builder.build(order).isTaxInvoice()).isFalse();
        assertThat(builder.build(order).gst()).isNull();

        AppSettings disabled = AppSettings.defaults(); // GST disabled by default
        InvoiceContent content = builder.build(order, disabled, Map.of(1L, "3004"));
        assertThat(content.isTaxInvoice()).isFalse();
        assertThat(content.gst()).isNull();
        // HSN is only surfaced on tax invoices; the plain path leaves it unset.
        assertThat(content.lineItems().get(0).hsnCode()).isNull();
    }

    @Test
    void gstEnabledIntraStateProducesCgstAndSgstWithHsnAndConsistentTotal() {
        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        // Seller in Maharashtra == order's Maharashtra -> intra-state.
        AppSettings settings = gstSettings("Maharashtra", new BigDecimal("5.00"), true);

        InvoiceContent content = builder.build(order, settings, Map.of(1L, "3004"));

        assertThat(content.isTaxInvoice()).isTrue();
        assertThat(content.lineItems().get(0).hsnCode()).isEqualTo("3004");
        GstComputation gst = content.gst().computation();
        assertThat(gst.intraState()).isTrue();
        assertThat(gst.cgstRate()).isEqualByComparingTo("2.50");
        assertThat(gst.sgstRate()).isEqualByComparingTo("2.50");
        assertThat(gst.cgstAmount().add(gst.sgstAmount())).isEqualByComparingTo(gst.totalTax());
        // Prices-inclusive: grand total equals the order total.
        assertThat(gst.grandTotal()).isEqualByComparingTo("240.00");
        assertThat(content.gst().gstin()).isEqualTo("23ABCDE1234F1Z5");
    }

    @Test
    void gstEnabledInterStateProducesIgst() {
        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        // Seller in Madhya Pradesh, order ships to Maharashtra -> inter-state.
        AppSettings settings = gstSettings("Madhya Pradesh", new BigDecimal("5.00"), true);

        InvoiceContent content = builder.build(order, settings, Map.of(1L, "3004"));

        GstComputation gst = content.gst().computation();
        assertThat(gst.intraState()).isFalse();
        assertThat(gst.igstRate()).isEqualByComparingTo("5.00");
        assertThat(gst.igstAmount()).isEqualByComparingTo(gst.totalTax());
        assertThat(gst.cgstAmount()).isEqualByComparingTo("0.00");
        assertThat(gst.grandTotal()).isEqualByComparingTo("240.00");
    }

    @Test
    void gstEnabledLineWithoutProductHsnLeavesHsnBlank() {
        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        AppSettings settings = gstSettings("Maharashtra", new BigDecimal("5.00"), true);

        // No HSN provided for product 1.
        InvoiceContent content = builder.build(order, settings, Map.of());

        assertThat(content.isTaxInvoice()).isTrue();
        assertThat(content.lineItems().get(0).hsnCode()).isNull();
    }

    // --- Per-product GST rate (Feature 2) -----------------------------------

    @Test
    void gstUsesPerProductRateWhenProvidedElseSettingsDefault() {
        OrderEntity order = order(PaymentStatus.FULLY_PAID,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        // Settings default is 5%, but product 1 carries an 18% per-product rate.
        AppSettings settings = gstSettings("Maharashtra", new BigDecimal("5.00"), true);

        InvoiceContent withOverride = builder.build(order, settings,
                Map.of(1L, "3004"), Map.of(1L, new BigDecimal("18.00")));
        assertThat(withOverride.gst().computation().ratePercent()).isEqualByComparingTo("18.00");

        // No per-product rate supplied -> falls back to the 5% settings default.
        InvoiceContent withDefault = builder.build(order, settings,
                Map.of(1L, "3004"), Map.of());
        assertThat(withDefault.gst().computation().ratePercent()).isEqualByComparingTo("5.00");
    }

    // --- Helper -------------------------------------------------------------

    private OrderEntity order(PaymentStatus paymentStatus, BigDecimal total,
                              BigDecimal received, BigDecimal remaining, BigDecimal cod) {
        OrderEntity order = new OrderEntity(
                "SHR-000123", OrderSource.SALESPERSON, 7L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.addLineItem(new OrderLineItem(1L, "Neem Capsules", 2,
                new BigDecimal("120.00"), new BigDecimal("240.00")));
        order.applyAmounts(total, received, remaining, cod, paymentStatus);
        order.setOrderStatus(com.shifa.oms.statemachine.OrderStatus.APPROVED);
        return order;
    }

    private AppSettings gstSettings(String sellerState, BigDecimal rate, boolean pricesIncludeGst) {
        AppSettings settings = new AppSettings();
        settings.setGstEnabled(true);
        settings.setGstin("23ABCDE1234F1Z5");
        settings.setLegalName("Shifa Herbal Remedies");
        settings.setAddressLine("Plot 5, Herbal Estate");
        settings.setCity("Indore");
        settings.setState(sellerState);
        settings.setStateCode("23");
        settings.setGstRatePercent(rate);
        settings.setPricesIncludeGst(pricesIncludeGst);
        return settings;
    }
}
