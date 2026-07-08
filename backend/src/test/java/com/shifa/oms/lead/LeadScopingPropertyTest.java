package com.shifa.oms.lead;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.lead.dto.LeadSummaryResponse;
import com.shifa.oms.order.LeadSource;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based test for salesperson scoping of lead reads (design §Correctness
 * Properties (5)).
 *
 * Feature: lead-management, Property 5: Salesperson scoping.
 *
 * <p>For any mix of owners, a salesperson's list/detail return exactly their own
 * leads and an out-of-scope detail id is a 404; an admin's queries are unscoped.
 *
 * <p>Real {@link LeadService} + real {@link com.shifa.oms.auth.SalespersonScopeResolver}
 * over an in-memory repository (interface mock) whose finders replay the owner-
 * scoping SQL — no concrete-class mocks. jqwik default 1000 tries (≥ 100).
 *
 * **Validates: Requirements 3.1, 3.2, 6.5, 7.2**
 */
class LeadScopingPropertyTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC);

    // Feature: lead-management, Property 5: Salesperson scoping
    // **Validates: Requirements 3.1, 3.2, 6.5, 7.2**
    @Property
    void salespersonSeesExactlyOwnLeadsAndAdminSeesAll(
            @ForAll @Size(min = 0, max = 40) List<@IntRange(min = 1, max = 5) Integer> owners,
            @ForAll @IntRange(min = 1, max = 5) int salespersonId) {

        List<LeadEntity> store = new ArrayList<>();
        for (int i = 0; i < owners.size(); i++) {
            store.add(LeadServiceTestSupport.lead(
                    i + 1L, "Lead" + i, LeadSource.WHATSAPP, owners.get(i), LeadStatus.NEW, null));
        }

        AtomicInteger auditCount = new AtomicInteger();
        LeadService service = LeadServiceTestSupport.service(
                LeadServiceTestSupport.inMemoryRepository(store), auditCount, CLOCK);

        AuthPrincipal salesperson =
                new AuthPrincipal((long) salespersonId, "sales" + salespersonId, Role.SALESPERSON);
        AuthPrincipal admin = new AuthPrincipal(99L, "admin", Role.ADMIN);

        // --- Salesperson list: exactly their own leads.
        List<LeadSummaryResponse> visible = service.list(null, null, null, salesperson);
        assertThat(visible).allMatch(l -> l.ownerUserId() == salespersonId);
        long expectedOwn = store.stream().filter(l -> l.getOwnerUserId() == salespersonId).count();
        assertThat(visible).hasSize((int) expectedOwn);

        // --- Admin list: unscoped (every lead).
        assertThat(service.list(null, null, null, admin)).hasSize(store.size());

        // --- Detail scoping: own = ok; out-of-scope id = 404.
        for (LeadEntity lead : store) {
            long id = lead.getId();
            if (lead.getOwnerUserId() == salespersonId) {
                assertThat(service.detail(id, salesperson).id()).isEqualTo(id);
            } else {
                assertThatThrownBy(() -> service.detail(id, salesperson))
                        .isInstanceOf(ResourceNotFoundException.class);
            }
            // Admin can always read.
            assertThat(service.detail(id, admin).id()).isEqualTo(id);
        }
    }
}
