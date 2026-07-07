package com.shifa.oms.invoice;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight rendering test for the company logo on invoices (Feature B). We do
 * not parse PDF internals; we assert the renderer produces a valid, non-empty
 * PDF (magic {@code %PDF-} header) both with a logo present and absent, and that
 * embedding a logo never throws.
 */
class InvoiceLogoRenderTest {

    private final InvoicePdfRenderer renderer = new InvoicePdfRenderer();
    private final InvoiceContentBuilder builder = new InvoiceContentBuilder();

    private static byte[] pngLogo() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private InvoiceContent content() {
        OrderEntity order = new OrderEntity(
                "SHR-000123", OrderSource.SALESPERSON, 7L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.addLineItem(new OrderLineItem(1L, "Neem Capsules", 2,
                new BigDecimal("120.00"), new BigDecimal("240.00")));
        order.applyAmounts(new BigDecimal("240.00"), new BigDecimal("240.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), PaymentStatus.FULLY_PAID);
        order.setOrderStatus(com.shifa.oms.statemachine.OrderStatus.APPROVED);
        return builder.build(order);
    }

    private static void assertIsPdf(byte[] pdf) {
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .startsWith("%PDF-");
    }

    @Test
    void rendersInvoiceWithLogoWithoutThrowing() throws Exception {
        byte[] pdf = renderer.render(content(), pngLogo());
        assertIsPdf(pdf);
    }

    @Test
    void rendersInvoiceWithoutLogoFallsBackToTextHeader() {
        byte[] pdf = renderer.render(content(), null);
        assertIsPdf(pdf);
    }

    @Test
    void invalidLogoBytesFallBackGracefullyToTextHeader() {
        // Undecodable "image" bytes must not fail the render (falls back to text).
        byte[] pdf = renderer.render(content(), new byte[] {1, 2, 3, 4, 5});
        assertIsPdf(pdf);
    }
}
