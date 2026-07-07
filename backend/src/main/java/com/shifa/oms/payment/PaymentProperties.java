package com.shifa.oms.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the online-payments module, bound from {@code app.payment.*}
 * (base config in {@code application.yml}).
 *
 * @param mode     gateway backend: {@code SANDBOX} (default, deterministic fake,
 *                 no real money / no network) or {@code RAZORPAY} (real provider
 *                 stub)
 * @param currency ISO currency code for gateway sessions (default {@code INR})
 * @param enabled  master on/off switch for the online-payment endpoints
 * @param sandbox  sandbox-gateway settings (signing secret)
 * @param razorpay Razorpay credentials (only used when {@code mode=RAZORPAY})
 */
@ConfigurationProperties(prefix = "app.payment")
public record PaymentProperties(
        String mode,
        String currency,
        Boolean enabled,
        Sandbox sandbox,
        Razorpay razorpay) {

    public PaymentProperties {
        if (mode == null || mode.isBlank()) {
            mode = "SANDBOX";
        }
        if (currency == null || currency.isBlank()) {
            currency = "INR";
        }
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (sandbox == null) {
            sandbox = new Sandbox(null);
        }
        if (razorpay == null) {
            razorpay = new Razorpay(null, null);
        }
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean isSandbox() {
        return "SANDBOX".equalsIgnoreCase(mode);
    }

    /**
     * Sandbox-gateway settings.
     *
     * @param secret server-only HMAC secret used to sign/verify test payments;
     *               it never reaches the client
     */
    public record Sandbox(String secret) {
        public Sandbox {
            if (secret == null || secret.isBlank()) {
                secret = "shifa-sandbox-payment-secret-change-me";
            }
        }
    }

    /**
     * Razorpay credentials (real integration).
     *
     * @param keyId     the public key id (safe to expose to the browser checkout)
     * @param keySecret the private key secret (server-only; used for signature
     *                  verification — must never reach the browser)
     */
    public record Razorpay(String keyId, String keySecret) {
    }
}
