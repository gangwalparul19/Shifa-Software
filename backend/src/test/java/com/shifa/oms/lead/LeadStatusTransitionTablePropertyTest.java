package com.shifa.oms.lead;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the pure {@link LeadStatus} transition table (design
 * §State Machine, §Correctness Properties 1 &amp; 2).
 *
 * <p>Pure and in-memory: exercises {@link LeadStatus} against an
 * <em>independent</em> copy of the design §State Machine table declared here, so
 * the production table cannot silently drift from the specification. No Spring,
 * database, or Mockito mocks. Each {@code @Property} runs the jqwik default of
 * 1000 tries, well above the required minimum of 100 iterations.
 */
class LeadStatusTransitionTablePropertyTest {

    /** The design §State Machine legal-transition table, restated independently. */
    private static final Map<LeadStatus, Set<LeadStatus>> EXPECTED = expectedTable();

    /** The terminal statuses (design §State Machine): WON, LOST. */
    private static final Set<LeadStatus> TERMINAL = EnumSet.of(LeadStatus.WON, LeadStatus.LOST);

    private static Map<LeadStatus, Set<LeadStatus>> expectedTable() {
        Map<LeadStatus, Set<LeadStatus>> t = new EnumMap<>(LeadStatus.class);
        t.put(LeadStatus.NEW, EnumSet.of(LeadStatus.CONTACTED, LeadStatus.LOST));
        t.put(LeadStatus.CONTACTED, EnumSet.of(LeadStatus.QUOTED, LeadStatus.LOST));
        t.put(LeadStatus.QUOTED, EnumSet.of(LeadStatus.LOST));
        t.put(LeadStatus.WON, EnumSet.noneOf(LeadStatus.class));
        t.put(LeadStatus.LOST, EnumSet.noneOf(LeadStatus.class));
        return t;
    }

    // Feature: lead-management, Property 1: Lead transition legality matches the table
    // **Validates: Requirements 2.2, 2.4, 2.7**
    @Property
    void transitionLegalityMatchesTheTable(@ForAll("statuses") LeadStatus from,
                                           @ForAll("statuses") LeadStatus to) {
        Set<LeadStatus> expectedTargets = EXPECTED.get(from);

        // canTransitionTo agrees with the independent table for every (from, to).
        assertThat(LeadStatus.canTransitionTo(from, to)).isEqualTo(expectedTargets.contains(to));
        assertThat(from.canTransitionTo(to)).isEqualTo(expectedTargets.contains(to));

        // allowedTargets() reproduces the specified target set exactly.
        assertThat(LeadStatus.allowedTargets(from)).isEqualTo(expectedTargets);
        assertThat(from.allowedTargets()).isEqualTo(expectedTargets);

        // Terminal classification matches the design set, and terminal states have
        // no outgoing transitions (Req 2.4, 2.7).
        boolean terminal = TERMINAL.contains(from);
        assertThat(LeadStatus.isTerminal(from)).isEqualTo(terminal);
        assertThat(from.isTerminal()).isEqualTo(terminal);
        if (terminal) {
            assertThat(from.allowedTargets()).isEmpty();
            assertThat(from.canTransitionTo(to)).isFalse();
        } else {
            assertThat(from.allowedTargets()).isNotEmpty();
        }
    }

    // Feature: lead-management, Property 2: WON is unreachable by manual status change
    // **Validates: Requirements 2.5, 4.2**
    @Property
    void wonIsNeverAManualTarget(@ForAll("statuses") LeadStatus from) {
        // WON appears in no state's allowed-target set — it is Convert-only.
        assertThat(LeadStatus.allowedTargets(from)).doesNotContain(LeadStatus.WON);
        assertThat(LeadStatus.canTransitionTo(from, LeadStatus.WON)).isFalse();
        assertThat(from.canTransitionTo(LeadStatus.WON)).isFalse();
    }

    @Provide
    Arbitrary<LeadStatus> statuses() {
        return Arbitraries.of(LeadStatus.values());
    }
}
