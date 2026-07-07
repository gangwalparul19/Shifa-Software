package com.shifa.oms.payment;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SandboxPaymentGateway}: the create → verify round-trip
 * succeeds with the server-signed token, and any tampering (wrong signature or
 * altered payment id) fails verification. Runs with no database and no network.
 */
class SandboxPaymentGatewayTest {

    private final SandboxPaymentGateway gateway = new SandboxPaymentGateway(
            new PaymentProperties("SANDBOX", "INR", true,
                    new PaymentProperties.Sandbox("unit-test-secret"), null));

    private OrderEntity order() {
        OrderEntity order = new OrderEntity("ORD-1001", OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "1 Herbal St", "Pune", "Maharashtra", "411001");
        ReflectionTestUtils.setField(order, "id", 1001L);
        return order;
    }

    @Test
    void createSessionThenVerifyRoundTrips() {
        PaymentSession session = gateway.createSession(order(), 50000L, "INR");

        assertThat(session.gateway()).isEqualTo("SANDBOX");
        assertThat(session.gatewayOrderId()).startsWith("SBX_ORD_ORD-1001_");
        assertThat(session.amountPaise()).isEqualTo(50000L);
        assertThat(session.currency()).isEqualTo("INR");
        // No provider key exists for the sandbox and none is leaked to the client.
        assertThat(session.keyId()).isNull();
        assertThat(session.clientToken()).contains(SandboxPaymentGateway.TOKEN_SEPARATOR);

        String[] parts = session.clientToken().split(SandboxPaymentGateway.TOKEN_SEPARATOR, 2);
        String paymentId = parts[0];
        String signature = parts[1];

        PaymentVerification verification =
                gateway.verify(session.gatewayOrderId(), paymentId, signature);

        assertThat(verification.success()).isTrue();
        assertThat(verification.gatewayPaymentId()).isEqualTo(paymentId);
    }

    @Test
    void verifyRejectsWrongSignature() {
        PaymentSession session = gateway.createSession(order(), 50000L, "INR");
        String paymentId = session.clientToken().split(SandboxPaymentGateway.TOKEN_SEPARATOR, 2)[0];

        PaymentVerification verification =
                gateway.verify(session.gatewayOrderId(), paymentId, "deadbeef");

        assertThat(verification.success()).isFalse();
    }

    @Test
    void verifyRejectsTamperedPaymentId() {
        PaymentSession session = gateway.createSession(order(), 50000L, "INR");
        String signature = session.clientToken().split(SandboxPaymentGateway.TOKEN_SEPARATOR, 2)[1];

        PaymentVerification verification =
                gateway.verify(session.gatewayOrderId(), "SBX_PAY_tampered", signature);

        assertThat(verification.success()).isFalse();
    }
}
