package com.shifa.oms.lead;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.lead.dto.LeadSummaryResponse;
import com.shifa.oms.order.LeadSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for due-follow-up membership (design §Correctness
 * Properties (8)).
 *
 * Feature: lead-management, Property 8: Due-follow-ups membership.
 *
 * <p>The due-follow-ups result for a user equals exactly their non-terminal leads
 * whose {@code follow_up_date <= today}; leads without a follow-up date are
 * excluded. The pure predicate {@link LeadService#isDueFollowUp} is validated
 * directly, and the scoped service view is validated against it.
 *
 * <p>Real {@link LeadService} over an in-memory repository (interface mock) whose
 * due-finder replays the pure predicate over the owner scope — no concrete-class
 * mocks. jqwik default 1000 tries (≥ 100).
 *
 * **Validates: Requirements 5.2, 5.4**
 */
class LeadDueFollowUpsPropertyTest {

    private static final LocalDate TODAY = LocalDate.parse("2024-06-01");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC);

    private record LeadSpec(int owner, LeadStatus status, LocalDate followUpDate) {
    }

    // Feature: lead-management, Property 8: Due-follow-ups membership (pure predicate)
    // **Validates: Requirements 5.2, 5.4**
    @Property
    void pureDuePredicateMatchesDefinition(@ForAll("statuses") LeadStatus status,
                                           @ForAll("dates") LocalDate followUpDate) {
        boolean expected = !LeadStatus.isTerminal(status)
                && followUpDate != null
                && !followUpDate.isAfter(TODAY);
        assertThat(LeadService.isDueFollowUp(status, followUpDate, TODAY)).isEqualTo(expected);
    }

    // Feature: lead-management, Property 8: Due-follow-ups membership (scoped service view)
    // **Validates: Requirements 5.2, 5.4**
    @Property
    void dueFollowUpsReturnsExactlyOwnDueNonTerminalLeads(
            @ForAll @Size(min = 0, max = 40) List<@net.jqwik.api.From("specs") LeadSpec> specs,
            @ForAll("ownerIds") int salespersonId) {

        List<LeadEntity> store = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            LeadSpec s = specs.get(i);
            store.add(LeadServiceTestSupport.lead(
                    i + 1L, "Lead" + i, LeadSource.WHATSAPP, s.owner(), s.status(), s.followUpDate()));
        }

        AtomicInteger auditCount = new AtomicInteger();
        LeadService service = LeadServiceTestSupport.service(
                LeadServiceTestSupport.inMemoryRepository(store), auditCount, CLOCK);

        AuthPrincipal salesperson =
                new AuthPrincipal((long) salespersonId, "sales" + salespersonId, Role.SALESPERSON);

        // Reference set: the salesperson's own leads that are due per the predicate.
        List<Long> expected = new ArrayList<>();
        for (LeadEntity l : store) {
            if (l.getOwnerUserId() == salespersonId
                    && LeadService.isDueFollowUp(l.getStatus(), l.getFollowUpDate(), TODAY)) {
                expected.add(l.getId());
            }
        }

        List<Long> actual = service.dueFollowUps(salesperson).stream()
                .map(LeadSummaryResponse::id)
                .toList();

        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        // Nothing terminal or without a follow-up date leaks in.
        for (LeadSummaryResponse due : service.dueFollowUps(salesperson)) {
            assertThat(due.ownerUserId()).isEqualTo((long) salespersonId);
            assertThat(due.followUpDate()).isNotNull();
            assertThat(due.followUpDate()).isBeforeOrEqualTo(TODAY);
            assertThat(due.status()).isIn(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.QUOTED);
        }
    }

    @Provide
    Arbitrary<LeadStatus> statuses() {
        return Arbitraries.of(LeadStatus.values());
    }

    @Provide
    Arbitrary<LocalDate> dates() {
        // Dates straddling today (2024-06-01), plus null (no follow-up).
        return Arbitraries.integers().between(-7, 7)
                .map(TODAY::plusDays)
                .injectNull(0.25);
    }

    @Provide
    Arbitrary<LeadSpec> specs() {
        Arbitrary<Integer> owner = Arbitraries.integers().between(1, 3);
        Arbitrary<LeadStatus> status = Arbitraries.of(LeadStatus.values());
        Arbitrary<LocalDate> date = dates();
        return Combinators.combine(owner, status, date).as(LeadSpec::new);
    }

    @Provide
    Arbitrary<Integer> ownerIds() {
        return Arbitraries.integers().between(1, 3);
    }
}
