package com.shifa.oms.lead;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.dto.CreateLeadRequest;
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
 * Property-based test for lead-capture validation (design §Correctness Properties (6)).
 *
 * Feature: lead-management, Property 6: Capture validation.
 *
 * <p>A lead is created iff it has a non-blank name and an in-set {@link LeadSource}
 * (with an {@code OTHER}/source note ≤200 and, when present, a 10-digit mobile);
 * otherwise it is rejected with a 400 and nothing is persisted. A created lead
 * starts {@link LeadStatus#NEW} with owner = actor and exactly one creation
 * history row (from = null), and records one audit event.
 *
 * <p>Exercises a real {@link LeadService} over an in-memory repository (interface
 * mock) and a recording audit — no Mockito mocks of concrete classes. Each
 * {@code @Property} runs the jqwik default of 1000 tries (≥ 100).
 *
 * **Validates: Requirements 1.1, 1.2, 1.6, 2.1**
 */
class LeadCaptureValidationPropertyTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final long OWNER_ID = 7L;
    private static final AuthPrincipal ACTOR = new AuthPrincipal(OWNER_ID, "sales7", Role.SALESPERSON);

    private record Case(String name, LeadSource source, String sourceNote, String mobile) {
    }

    // Feature: lead-management, Property 6: Capture validation
    // **Validates: Requirements 1.1, 1.2, 1.6, 2.1**
    @Property
    void leadIsCapturedExactlyWhenValid(@ForAll("cases") Case c) {
        List<LeadEntity> store = new ArrayList<>();
        AtomicInteger auditCount = new AtomicInteger();
        LeadService service = LeadServiceTestSupport.service(
                LeadServiceTestSupport.inMemoryRepository(store), auditCount, CLOCK);

        boolean nameOk = c.name() != null && !c.name().isBlank();
        boolean sourceOk = c.source() != null;
        boolean noteOk = c.sourceNote() == null || c.sourceNote().length() <= 200;
        boolean mobileOk = c.mobile() == null || c.mobile().matches("\\d{10}");
        boolean expectValid = nameOk && sourceOk && noteOk && mobileOk;

        CreateLeadRequest request = new CreateLeadRequest(
                c.name(), c.source(), c.sourceNote(), c.mobile(), null, null, null);

        if (expectValid) {
            LeadResponse response = service.capture(request, ACTOR);

            // Created NEW, owned by the actor (Req 1.5, 2.1).
            assertThat(response.status()).isEqualTo(LeadStatus.NEW);
            assertThat(response.ownerUserId()).isEqualTo(OWNER_ID);
            assertThat(store).hasSize(1);

            // Exactly one creation history row: from = null → NEW.
            assertThat(response.statusHistory()).hasSize(1);
            LeadResponse.StatusHistoryEntry creation = response.statusHistory().get(0);
            assertThat(creation.fromStatus()).isNull();
            assertThat(creation.toStatus()).isEqualTo(LeadStatus.NEW);
            assertThat(creation.actor()).isEqualTo("sales7");

            // One audit event for the capture.
            assertThat(auditCount.get()).isEqualTo(1);
        } else {
            Throwable thrown = catchThrowable(() -> service.capture(request, ACTOR));
            assertThat(thrown).isInstanceOf(ValidationException.class);
            // Nothing persisted, nothing audited (Req 1.6).
            assertThat(store).isEmpty();
            assertThat(auditCount.get()).isZero();
        }
    }

    @Provide
    Arbitrary<Case> cases() {
        Arbitrary<String> names = Arbitraries.oneOf(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(20),
                Arbitraries.of("", " ", "   ", "\t"))
                .injectNull(0.1);
        Arbitrary<LeadSource> sources = Arbitraries.of(LeadSource.values()).injectNull(0.15);
        Arbitrary<String> notes = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(0).ofMaxLength(250).injectNull(0.3);
        Arbitrary<String> mobiles = Arbitraries.oneOf(
                Arbitraries.strings().withChars('0', '9').ofLength(10),       // valid
                Arbitraries.strings().withChars('0', '9').ofMinLength(1).ofMaxLength(9), // too short
                Arbitraries.of("abcdefghij", "12345678901"))                  // non-digit / too long
                .injectNull(0.3);
        return Combinators.combine(names, sources, notes, mobiles).as(Case::new);
    }
}
