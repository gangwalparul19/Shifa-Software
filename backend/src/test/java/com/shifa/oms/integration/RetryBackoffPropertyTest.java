package com.shifa.oms.integration;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: shopify-quikshipx-order-sync, Property 5: Retry backoff is exponential,
 * monotone and bounded.
 *
 * <p>For any attempt number and any base delay, the next retry delay equals the base
 * multiplied by two raised to the power of (attempt − 1), capped at the configured
 * maximum backoff, and the delay sequence is non-decreasing in the attempt number.
 *
 * <p>Validates: Requirements 2.9, 5.6
 */
class RetryBackoffPropertyTest {

    @Property(tries = 1000)
    void delayDoublesUntilItReachesTheCeiling(
            @ForAll @IntRange(min = 1, max = 8) int attempt,
            @ForAll @LongRange(min = 1, max = 120) long baseSeconds) {

        Duration base = Duration.ofSeconds(baseSeconds);
        // A ceiling high enough that doubling is never clipped for these inputs.
        Duration ceiling = Duration.ofDays(365);

        Duration delay = RetryBackoff.nextDelay(attempt, base, ceiling);

        long expectedSeconds = baseSeconds * (1L << (attempt - 1));
        assertThat(delay).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Property(tries = 1000)
    void delayIsNonDecreasingInAttempt(
            @ForAll @IntRange(min = 1, max = 30) int attempt,
            @ForAll @LongRange(min = 1, max = 600) long baseSeconds,
            @ForAll @LongRange(min = 1, max = 3600) long maxSeconds) {

        Duration base = Duration.ofSeconds(baseSeconds);
        Duration ceiling = Duration.ofSeconds(maxSeconds);

        Duration earlier = RetryBackoff.nextDelay(attempt, base, ceiling);
        Duration later = RetryBackoff.nextDelay(attempt + 1, base, ceiling);

        // Monotonicity is what stops a retry storm: each attempt waits at least as
        // long as the one before it.
        assertThat(later).isGreaterThanOrEqualTo(earlier);
    }

    @Property(tries = 1000)
    void delayNeverExceedsTheCeiling(
            @ForAll @IntRange(min = 1, max = 200) int attempt,
            @ForAll @LongRange(min = 1, max = 600) long baseSeconds,
            @ForAll @LongRange(min = 1, max = 3600) long maxSeconds) {

        Duration ceiling = Duration.ofSeconds(maxSeconds);

        Duration delay = RetryBackoff.nextDelay(attempt, Duration.ofSeconds(baseSeconds), ceiling);

        // Without the cap a large attempt number would schedule a retry so far out
        // that the event is effectively lost, and doubling would overflow.
        assertThat(delay).isLessThanOrEqualTo(ceiling);
        assertThat(delay).isPositive();
    }

    @Property(tries = 200)
    void delayIsAlwaysPositiveForDegenerateInputs(
            @ForAll @IntRange(min = -50, max = 50) int attempt) {

        // Null / zero / negative configuration must fall back to the defaults rather
        // than producing a zero delay, which would busy-loop the drainer.
        assertThat(RetryBackoff.nextDelay(attempt, null)).isPositive();
        assertThat(RetryBackoff.nextDelay(attempt, Duration.ZERO)).isPositive();
        assertThat(RetryBackoff.nextDelay(attempt, Duration.ofSeconds(-5))).isPositive();
        assertThat(RetryBackoff.nextDelay(attempt, Duration.ofSeconds(30), Duration.ZERO)).isPositive();
        assertThat(RetryBackoff.nextDelay(attempt, Duration.ofSeconds(30), null)).isPositive();
    }

    @Property(tries = 500)
    void attemptBudgetIsExhaustedExactlyAtTheLimit(
            @ForAll @IntRange(min = 0, max = 20) int attemptsMade,
            @ForAll @IntRange(min = 1, max = 10) int maxAttempts) {

        assertThat(RetryBackoff.hasAttemptsLeft(attemptsMade, maxAttempts))
                .isEqualTo(attemptsMade < maxAttempts);
    }

    @Property(tries = 100)
    void firstAttemptUsesTheBaseDelayExactly(
            @ForAll @LongRange(min = 1, max = 600) long baseSeconds) {

        assertThat(RetryBackoff.nextDelay(1, Duration.ofSeconds(baseSeconds)))
                .isEqualTo(Duration.ofSeconds(baseSeconds));
    }

    @Property(tries = 1)
    void thePublicationLadderIsThirtySixtyOneTwentyTwoFortyFourEighty() {
        // The concrete sequence the spec fixes for QuikShipX publication (Req 5.6),
        // pinned as an example so a refactor cannot silently reshape it.
        Duration base = Duration.ofSeconds(30);
        assertThat(RetryBackoff.nextDelay(1, base)).isEqualTo(Duration.ofSeconds(30));
        assertThat(RetryBackoff.nextDelay(2, base)).isEqualTo(Duration.ofSeconds(60));
        assertThat(RetryBackoff.nextDelay(3, base)).isEqualTo(Duration.ofSeconds(120));
        assertThat(RetryBackoff.nextDelay(4, base)).isEqualTo(Duration.ofSeconds(240));
        assertThat(RetryBackoff.nextDelay(5, base)).isEqualTo(Duration.ofSeconds(480));
    }
}
