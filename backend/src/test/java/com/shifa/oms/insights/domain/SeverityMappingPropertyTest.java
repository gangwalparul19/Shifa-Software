package com.shifa.oms.insights.domain;

import com.shifa.oms.adminnotification.AdminNotification;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.GenerationMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the total severity → notification-severity mapping on
 * {@link InsightSeverity} (design &sect;Correctness Properties (8)).
 *
 * Feature: statistical-insights-engine, Property 8: Severity mapping total.
 *
 * <p>Every {@link InsightSeverity} maps to a non-null
 * {@link AdminNotification} {@code SEVERITY_*} string, and only WARNING/DANGER
 * are "notifiable". Purely exercises the enum — no mocks. jqwik default 1000
 * tries (&ge; 100), which enumerates all three severities many times over.
 *
 * **Validates: Requirements 10.1, 10.2**
 */
class SeverityMappingPropertyTest {

    // Feature: statistical-insights-engine, Property 8: Severity mapping total
    // **Validates: Requirements 10.1, 10.2**
    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    void everySeverityMapsToANotificationSeverityString(@ForAll("severities") InsightSeverity severity) {
        String mapped = severity.toNotificationSeverity();
        assertThat(mapped).isNotNull();

        String expected = switch (severity) {
            case INFO -> AdminNotification.SEVERITY_INFO;
            case WARNING -> AdminNotification.SEVERITY_WARNING;
            case DANGER -> AdminNotification.SEVERITY_DANGER;
        };
        assertThat(mapped).isEqualTo(expected);

        // Only WARNING/DANGER are notifiable (Req 10.1, 10.2).
        boolean expectedNotifiable = severity == InsightSeverity.WARNING
                || severity == InsightSeverity.DANGER;
        assertThat(severity.isNotifiable()).isEqualTo(expectedNotifiable);
    }

    @Provide
    Arbitrary<InsightSeverity> severities() {
        return Arbitraries.of(InsightSeverity.values());
    }
}
