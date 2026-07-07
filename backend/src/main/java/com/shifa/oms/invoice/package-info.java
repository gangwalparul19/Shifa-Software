/**
 * Invoice module: generates a professional per-order PDF invoice on demand.
 *
 * <p>Follows the same content-vs-rendering split as the {@code label} module: a
 * pure {@link com.shifa.oms.invoice.InvoiceContentBuilder} assembles an
 * {@link com.shifa.oms.invoice.InvoiceContent} model from an
 * {@link com.shifa.oms.order.OrderEntity}, and
 * {@link com.shifa.oms.invoice.InvoicePdfRenderer} turns that model into A4 PDF
 * bytes using OpenPDF ({@code com.lowagie.text}).
 * {@link com.shifa.oms.invoice.InvoiceService} wires the two together and exposes
 * a role-scoped path (by order id) and a public path (by order code), consumed
 * by the order and tracking controllers respectively.
 */
package com.shifa.oms.invoice;
