package com.shifa.oms.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the payments module: enables {@link PaymentProperties} binding from
 * {@code app.payment.*}. The active {@link PaymentGateway} bean is selected by
 * the {@code app.payment.mode} property — {@link SandboxPaymentGateway} by
 * default, {@link RazorpayPaymentGateway} when {@code mode=RAZORPAY}.
 */
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {
}
