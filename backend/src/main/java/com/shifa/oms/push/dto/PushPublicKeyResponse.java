package com.shifa.oms.push.dto;

/**
 * The VAPID public key + enabled flag the client needs to decide whether to
 * offer push and to subscribe (FEATURE-ROADMAP §8.3).
 *
 * @param enabled   whether server-side push is configured
 * @param publicKey the VAPID public key (empty when disabled)
 */
public record PushPublicKeyResponse(boolean enabled, String publicKey) {
}
