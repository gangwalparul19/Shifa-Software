package com.shifa.oms.integration;

import com.shifa.oms.integration.WebhookPayloadLimit.Verdict;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: shopify-quikshipx-order-sync, Property 4: Oversized bodies are rejected
 * before verification.
 *
 * <p>For any body length, any declared content length, and any configured maximum
 * between 256 kilobytes and 8 megabytes, the payload-limit verdict is
 * {@code TOO_LARGE} if and only if either length exceeds the maximum.
 *
 * <p>Validates: Requirements 2.8
 */
class WebhookPayloadLimitPropertyTest {

    private static final int MIN = WebhookPayloadLimit.MIN_CONFIGURABLE_BYTES;
    private static final int MAX = WebhookPayloadLimit.MAX_CONFIGURABLE_BYTES;

    @Property(tries = 1000)
    void tooLargeExactlyWhenEitherLengthExceedsTheMaximum(
            @ForAll @IntRange(min = MIN, max = MAX) int configuredMax,
            @ForAll @IntRange(min = 0, max = 12 * 1024 * 1024) int actualLength,
            @ForAll @LongRange(min = 0, max = 12L * 1024 * 1024) long declaredLength) {

        Verdict verdict = WebhookPayloadLimit.verdict(declaredLength, actualLength, configuredMax);

        boolean expectedTooLarge = declaredLength > configuredMax || actualLength > configuredMax;
        assertThat(verdict).isEqualTo(expectedTooLarge ? Verdict.TOO_LARGE : Verdict.ACCEPT);
    }

    @Property(tries = 500)
    void anAbsentContentLengthFallsBackToTheActualLength(
            @ForAll @IntRange(min = MIN, max = MAX) int configuredMax,
            @ForAll @IntRange(min = 0, max = 12 * 1024 * 1024) int actualLength) {

        // A chunked delivery carries no Content-Length, so the actual body length
        // must still be enforced or the guard is bypassable.
        Verdict verdict = WebhookPayloadLimit.verdict(null, actualLength, configuredMax);

        assertThat(verdict).isEqualTo(
                actualLength > configuredMax ? Verdict.TOO_LARGE : Verdict.ACCEPT);
    }

    @Property(tries = 500)
    void aLyingContentLengthCannotSmuggleAnOversizedBody(
            @ForAll @IntRange(min = MIN, max = MAX) int configuredMax,
            @ForAll @IntRange(min = 1, max = 4 * 1024 * 1024) int overshoot) {

        // Header claims a small body, the actual body is over the limit.
        long declaredSmall = 10L;
        int actualLength = configuredMax + overshoot;

        assertThat(WebhookPayloadLimit.verdict(declaredSmall, actualLength, configuredMax))
                .isEqualTo(Verdict.TOO_LARGE);
    }

    @Property(tries = 1000)
    void theConfiguredMaximumIsAlwaysClampedIntoThePermittedRange(
            @ForAll @IntRange(min = -1_000_000, max = 64 * 1024 * 1024) int configuredMax) {

        int clamped = WebhookPayloadLimit.clampMax(configuredMax);

        // A mis-set property must neither disable the guard nor reject every order.
        assertThat(clamped).isBetween(MIN, MAX);
        if (configuredMax >= MIN && configuredMax <= MAX) {
            assertThat(clamped).isEqualTo(configuredMax);
        }
    }

    @Property(tries = 200)
    void aBodyExactlyAtTheLimitIsAccepted(
            @ForAll @IntRange(min = MIN, max = MAX) int configuredMax) {

        // Boundary: the limit is inclusive, so a body of exactly max bytes is fine.
        assertThat(WebhookPayloadLimit.verdict((long) configuredMax, configuredMax, configuredMax))
                .isEqualTo(Verdict.ACCEPT);
        assertThat(WebhookPayloadLimit.verdict((long) configuredMax + 1, configuredMax, configuredMax))
                .isEqualTo(Verdict.TOO_LARGE);
    }
}
