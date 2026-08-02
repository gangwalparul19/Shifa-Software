package com.shifa.oms.integration;

import java.time.Duration;

/**
 * Pure exponential-backoff schedule for integration retries (Req 2.9, 5.6).
 *
 * <p>No Spring, no clock, no state — the delay is a total function of the attempt
 * number, which is what makes the schedule property-testable and makes a drainer's
 * retry timing reproducible.
 *
 * <p>The ladder is {@code base * 2^(attempt-1)}, capped: with a 30 second base that
 * is 30, 60, 120, 240, 480 seconds, matching the publication retry sequence the
 * spec fixes. The cap matters because without it attempt 20 would schedule a retry
 * years away, effectively losing the event.
 */
public final class RetryBackoff {

    /** Default first-retry delay. */
    public static final Duration DEFAULT_BASE = Duration.ofSeconds(30);

    /** Default ceiling, so a long-failing event still retries at a useful interval. */
    public static final Duration DEFAULT_MAX = Duration.ofMinutes(15);

    private RetryBackoff() {
        // Pure static helper.
    }

    /**
     * The delay before the given attempt, using the default ceiling.
     *
     * @param attempt the 1-based retry number; values below 1 are treated as 1
     * @param base    the first-retry delay; non-positive falls back to {@link #DEFAULT_BASE}
     */
    public static Duration nextDelay(int attempt, Duration base) {
        return nextDelay(attempt, base, DEFAULT_MAX);
    }

    /**
     * The delay before the given attempt.
     *
     * <p>Monotone non-decreasing in {@code attempt} and never above {@code max},
     * which together are the property the retry ladder relies on.
     *
     * @param attempt the 1-based retry number; values below 1 are treated as 1
     * @param base    the first-retry delay; non-positive falls back to {@link #DEFAULT_BASE}
     * @param max     the ceiling; non-positive falls back to {@link #DEFAULT_MAX}
     */
    public static Duration nextDelay(int attempt, Duration base, Duration max) {
        Duration effectiveBase = (base == null || base.isZero() || base.isNegative())
                ? DEFAULT_BASE : base;
        Duration ceiling = (max == null || max.isZero() || max.isNegative())
                ? DEFAULT_MAX : max;
        if (ceiling.compareTo(effectiveBase) < 0) {
            // A ceiling below the base would make the first retry shorter than
            // configured; the ceiling wins so the cap is never exceeded.
            return ceiling;
        }

        int n = Math.max(1, attempt);
        // Shift instead of Math.pow to stay exact, and stop doubling as soon as the
        // ceiling is reached so a large attempt number cannot overflow.
        Duration delay = effectiveBase;
        for (int i = 1; i < n; i++) {
            if (delay.compareTo(ceiling) >= 0) {
                return ceiling;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(ceiling) > 0 ? ceiling : delay;
    }

    /**
     * Whether another attempt is allowed.
     *
     * @param attemptsMade how many attempts have already been made
     * @param maxAttempts  the total attempt budget, including the first
     */
    public static boolean hasAttemptsLeft(int attemptsMade, int maxAttempts) {
        return attemptsMade < Math.max(1, maxAttempts);
    }
}
