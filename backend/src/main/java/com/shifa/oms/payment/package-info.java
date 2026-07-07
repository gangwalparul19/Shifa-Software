/**
 * Online payments module (Phase E — ROADMAP "Online payment gateway").
 *
 * <p>Integrates a payment gateway behind the swappable {@link
 * com.shifa.oms.payment.PaymentGateway} interface so the storefront checkout can
 * offer a "Pay Online" option alongside Cash&nbsp;on&nbsp;Delivery. The default
 * {@code app.payment.mode=SANDBOX} wires a deterministic, HMAC-signed fake
 * gateway ({@link com.shifa.oms.payment.SandboxPaymentGateway}) that completes
 * the initiate → confirm flow end-to-end with <strong>no real money and no
 * network</strong>. A documented {@link com.shifa.oms.payment.RazorpayPaymentGateway}
 * stub sits behind the same interface for a future real integration
 * ({@code app.payment.mode=RAZORPAY}).
 *
 * <p>On a verified confirmation the order is marked fully paid via
 * {@link com.shifa.oms.order.OrderService#markPaidOnline(Long)} (amount_received
 * = total, remaining = 0, cod_amount = 0, payment_status = FULLY_PAID) while its
 * lifecycle status stays in the normal approval pipeline. Because the order
 * becomes FULLY_PAID, settlement treats it as prepaid and closes it on delivery
 * (see {@code com.shifa.oms.reconciliation}). Every attempt is persisted as a
 * {@link com.shifa.oms.payment.PaymentTransaction} for admin visibility.
 */
package com.shifa.oms.payment;
