package com.shifa.oms.lead;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.dto.LeadResponse;
import com.shifa.oms.order.LeadSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Property-based test for the LOST-reason requirement (design §Correctness
 * Properties (3)).
 *
 * Feature: lead-management, Property 3: LOST requires and stores a reason.
 *
 * <p>A transition to {@link LeadStatus#LOST} is accepted iff a valid
 * {@link LostReason} is supplied (with a note ≤200); when accepted the reason is
 * persisted and the lead becomes terminal; when rejected (missing reason / over-
 * long note) it is a 400 and the lead is unchanged.
 *
 * <p>Real {@link LeadService} over an in-memory repository (interface mock) and a
 * recording audit — no concrete-class mocks. jqwik default 1000 tries (≥ 100).
 *
 * **Validates: Requirements 2.3, 2.4**
 */
class LeadLostReasonPropertyTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final long OWNER_ID = 7L;
    private static final AuthPrincipal ACTOR = new AuthPrincipal(OWNER_ID, "sales7", Role.SALESPERSON);

    private record Case(LeadStatus from, LostReason reason, String note) {
    }

    // Feature: lead-management, Property 3: LOST requires and stores a reason
    // **Validates: Requirements 2.3, 2.4**
    @Property
    void lostIsAcceptedExactlyWithAValidReason(@ForAll("cases") Case c) {
        List<LeadEntity> store = new ArrayList<>();
        LeadEntity lead = LeadServiceTestSupport.lead(
                42L, "Asha", LeadSource.WHATSAPP, OWNER_ID, c.from(), null);
        store.add(lead);
        int historyBefore = lead.getStatusHistory().size();

        AtomicInteger auditCount = new AtomicInteger();
        LeadService service = LeadServiceTestSupport.service(
                LeadServiceTestSupport.inMemoryRepository(store), auditCount, CLOCK);

        boolean noteOk = c.note() == null || c.note().length() <= 200;
        boolean expectValid = c.reason() != null && noteOk;

        if (expectValid) {
            LeadResponse response = service.transition(42L, LeadStatus.LOST, c.reason(), c.note(), ACTOR);

            // Accepted: LOST, terminal, reason persisted (Req 2.3, 2.4).
            assertThat(response.status()).isEqualTo(LeadStatus.LOST);
            assertThat(LeadStatus.isTerminal(response.status())).isTrue();
            assertThat(response.lostReason()).isEqualTo(c.reason());
            // Exactly one new history row for the transition.
            assertThat(lead.getStatusHistory()).hasSize(historyBefore + 1);
            assertThat(auditCount.get()).isEqualTo(1);
        } else {
            Throwable thrown = catchThrowable(
                    () -> service.transition(42L, LeadStatus.LOST, c.reason(), c.note(), ACTOR));
            assertThat(thrown).isInstanceOf(ValidationException.class);
            // Unchanged: still the original status, no reason, no new history, no audit.
            assertThat(lead.getStatus()).isEqualTo(c.from());
            assertThat(lead.getLostReason()).isNull();
            assertThat(lead.getStatusHistory()).hasSize(historyBefore);
            assertThat(auditCount.get()).isZero();
        }
    }

    @Provide
    Arbitrary<Case> cases() {
        // Non-terminal starting statuses from which LOST is a legal edge.
        Arbitrary<LeadStatus> from =
                Arbitraries.of(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.QUOTED);
        Arbitrary<LostReason> reason = Arbitraries.of(LostReason.values()).injectNull(0.4);
        Arbitrary<String> note = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(0).ofMaxLength(250).injectNull(0.4);
        return Combinators.combine(from, reason, note).as(Case::new);
    }
}
