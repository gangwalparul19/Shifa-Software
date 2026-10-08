package com.shifa.oms.lead;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.IllegalLeadTransitionException;
import com.shifa.oms.lead.dto.LeadResponse;
import com.shifa.oms.order.LeadSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Property-based test for the per-transition history-append invariant, and the
 * illegal-transition no-op (design §Correctness Properties (4) and (1) at the
 * service layer).
 *
 * Feature: lead-management, Property 4: Each successful transition appends
 * exactly one history row.
 *
 * <p>Over every {@code (from, to)} pair: an accepted manual transition advances
 * the status, appends exactly one {@link LeadStatusHistory} row (from/to/actor),
 * and records one audit event; an illegal transition (including any move to
 * {@link LeadStatus#WON}, or any change on a terminal lead) raises a 409 and
 * leaves the lead + its history unchanged with no audit.
 *
 * <p>Real {@link LeadService} over an in-memory repository (interface mock) and a
 * recording audit — no concrete-class mocks. jqwik default 1000 tries (≥ 100).
 *
 * **Validates: Requirements 2.6, 7.5 (and 2.2, 2.7)**
 */
class LeadTransitionHistoryPropertyTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final long OWNER_ID = 7L;
    private static final AuthPrincipal ACTOR = new AuthPrincipal(OWNER_ID, "sales7", Role.SALESPERSON);

    /** Independent §State Machine legal-transition table (WON is never a target). */
    private static final Map<LeadStatus, Set<LeadStatus>> LEGAL = legalTable();

    private static Map<LeadStatus, Set<LeadStatus>> legalTable() {
        Map<LeadStatus, Set<LeadStatus>> t = new EnumMap<>(LeadStatus.class);
        t.put(LeadStatus.NEW, EnumSet.of(LeadStatus.CONTACTED, LeadStatus.LOST));
        t.put(LeadStatus.CONTACTED, EnumSet.of(LeadStatus.QUOTED, LeadStatus.LOST));
        t.put(LeadStatus.QUOTED, EnumSet.of(LeadStatus.LOST));
        t.put(LeadStatus.WON, EnumSet.noneOf(LeadStatus.class));
        t.put(LeadStatus.LOST, EnumSet.noneOf(LeadStatus.class));
        return t;
    }

    // Feature: lead-management, Property 4: Each successful transition appends exactly one history row
    // **Validates: Requirements 2.6, 7.5**
    @Property
    void eachTransitionAppendsExactlyOneRowOrIsANoOp(@ForAll("statuses") LeadStatus from,
                                                     @ForAll("statuses") LeadStatus to) {
        List<LeadEntity> store = new ArrayList<>();
        LeadEntity lead = LeadServiceTestSupport.lead(
                42L, "Asha", LeadSource.WHATSAPP, OWNER_ID, from, null);
        store.add(lead);
        int historyBefore = lead.getStatusHistory().size();

        AtomicInteger auditCount = new AtomicInteger();
        LeadService service = LeadServiceTestSupport.service(
                LeadServiceTestSupport.inMemoryRepository(store), auditCount, CLOCK);

        boolean legal = LEGAL.get(from).contains(to);
        // LOST edges need a reason to be accepted; supply one so legal edges succeed.
        LostReason reason = (to == LeadStatus.LOST) ? LostReason.PRICE : null;

        if (legal) {
            LeadResponse response = service.transition(42L, to, reason, null, ACTOR);

            assertThat(response.status()).isEqualTo(to);
            assertThat(lead.getStatus()).isEqualTo(to);
            // Exactly one new history row, recording the transition faithfully.
            assertThat(lead.getStatusHistory()).hasSize(historyBefore + 1);
            LeadStatusHistory row = lead.getStatusHistory().get(lead.getStatusHistory().size() - 1);
            assertThat(row.getFromStatus()).isEqualTo(from);
            assertThat(row.getToStatus()).isEqualTo(to);
            assertThat(row.getActor()).isEqualTo("sales7");
            // Exactly one audit event.
            assertThat(auditCount.get()).isEqualTo(1);
        } else {
            Throwable thrown = catchThrowable(() -> service.transition(42L, to, reason, null, ACTOR));
            assertThat(thrown).isInstanceOf(IllegalLeadTransitionException.class);
            // Unchanged: status, history, and no audit (Req 2.7).
            assertThat(lead.getStatus()).isEqualTo(from);
            assertThat(lead.getStatusHistory()).hasSize(historyBefore);
            assertThat(auditCount.get()).isZero();
        }
    }

    @Provide
    Arbitrary<LeadStatus> statuses() {
        return Arbitraries.of(LeadStatus.values());
    }
}
