package com.shifa.oms.integration;

/**
 * Pure size guard for inbound webhook bodies (Req 2.8).
 *
 * <p>Evaluated <b>before</b> signature verification on purpose: computing an HMAC
 * over an oversized body is wasted work and a cheap denial-of-service lever, so an
 * over-limit delivery is rejected without hashing it and without persisting any
 * part of it.
 *
 * <p>Note that {@code spring.servlet.multipart} limits do not apply here — those
 * govern multipart uploads, and a webhook body is raw JSON, so this check is the
 * only thing standing between the endpoint and an unbounded request.
 */
public final class WebhookPayloadLimit {

    /** Default maximum accepted body size. */
    public static final int DEFAULT_MAX_BYTES = 1024 * 1024;

    /** Smallest configurable maximum, below which normal orders would be rejected. */
    public static final int MIN_CONFIGURABLE_BYTES = 256 * 1024;

    /** Largest configurable maximum. */
    public static final int MAX_CONFIGURABLE_BYTES = 8 * 1024 * 1024;

    /** The verdict for one delivery. */
    public enum Verdict {
        ACCEPT,
        TOO_LARGE
    }

    private WebhookPayloadLimit() {
        // Pure static helper.
    }

    /**
     * Clamps a configured maximum into the permitted range, so a mis-set property
     * cannot disable the guard or reject every delivery.
     */
    public static int clampMax(int configuredMaxBytes) {
        if (configuredMaxBytes < MIN_CONFIGURABLE_BYTES) {
            return MIN_CONFIGURABLE_BYTES;
        }
        return Math.min(configuredMaxBytes, MAX_CONFIGURABLE_BYTES);
    }

    /**
     * Decides whether a delivery is within the size limit.
     *
     * <p>Both the declared {@code Content-Length} and the actual body length are
     * checked: the header lets us reject before reading, and the actual length
     * catches a body that lied about its size or arrived chunked with no header.
     *
     * @param declaredContentLength the {@code Content-Length} header, or {@code null}/negative when absent
     * @param actualBodyLength      the number of bytes actually read
     * @param configuredMaxBytes    the configured maximum, clamped by {@link #clampMax}
     */
    public static Verdict verdict(Long declaredContentLength, int actualBodyLength, int configuredMaxBytes) {
        int max = clampMax(configuredMaxBytes);
        if (declaredContentLength != null && declaredContentLength > max) {
            return Verdict.TOO_LARGE;
        }
        return actualBodyLength > max ? Verdict.TOO_LARGE : Verdict.ACCEPT;
    }
}
