package com.shifa.oms.performance;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadRepository;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;
import com.shifa.oms.performance.dto.TeamPerformanceResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link TeamPerformanceService} — the team-lead
 * performance rollup. Verifies the overall KPI aggregation across the team's
 * leaderboard, the lead-source conversion ranking ("which source converts best"),
 * and the empty-team path. The leaderboard itself is provided by a recording
 * subclass of {@link SalespersonPerformanceService} (Java 25 can't mock concrete
 * classes), so this isolates the team service's own aggregation logic.
 */
class TeamPerformanceServiceTest {

    private final LeadRepository leadRepository = mock(LeadRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    // Real resolver over the mocked repo (Java 25 can't mock the concrete resolver);
    // its team lookup is driven by stubbing userRepository.findIdsByTeamLeadId.
    private final SalespersonScopeResolver scopeResolver = new SalespersonScopeResolver(userRepository);

    private static AuthPrincipal teamLead() {
        return new AuthPrincipal(5L, "lead", Role.TEAM_LEAD);
    }

    /** A recording {@link SalespersonPerformanceService} returning a fixed leaderboard. */
    private static SalespersonPerformanceService stubPerf(List<SalespersonPerformanceSummary> rows) {
        return new SalespersonPerformanceService(
                mock(OrderRepository.class), mock(UserRepository.class), mock(LeadRepository.class)) {
            @Override
            public List<SalespersonPerformanceSummary> leaderboardFor(Collection<Long> memberIds) {
                return memberIds != null && memberIds.isEmpty() ? List.of() : rows;
            }
        };
    }

    private static SalespersonPerformanceSummary summary(
            long id, String name, long ordersTotal, long ordersMonth,
            String revenueMonth, long delivered, long failed) {
        return new SalespersonPerformanceSummary(
                id, "u" + id, name, true, "VERIFIED",
                ordersTotal, ordersMonth, 0,
                new BigDecimal("1000.00"), new BigDecimal(revenueMonth),
                delivered, failed, 0.0, BigDecimal.ZERO);
    }

    private static LeadEntity lead(LeadSource source, LeadStatus status) {
        LeadEntity l = new LeadEntity("Cust", source, 11L);
        l.setStatus(status);
        return l;
    }

    @Test
    void rollsUpTeamKpisLeaderboardAndSourceConversion() {
        when(userRepository.findIdsByTeamLeadId(5L)).thenReturn(List.of(11L, 12L));
        // Anita leads this month; sorted-first by the (stubbed) leaderboard.
        List<SalespersonPerformanceSummary> rows = List.of(
                summary(11L, "Anita", 30, 12, "5000.00", 10, 2),
                summary(12L, "Rahul", 20, 8, "3000.00", 6, 4));
        when(leadRepository.findAllScopedIn(List.of(11L, 12L))).thenReturn(List.of(
                lead(LeadSource.WHATSAPP, LeadStatus.WON),
                lead(LeadSource.WHATSAPP, LeadStatus.LOST),
                lead(LeadSource.INSTAGRAM, LeadStatus.WON)));

        TeamPerformanceService service =
                new TeamPerformanceService(stubPerf(rows), leadRepository, userRepository, scopeResolver);
        TeamPerformanceResponse res = service.forCaller(teamLead());

        assertThat(res.memberCount()).isEqualTo(2);
        assertThat(res.ordersTotal()).isEqualTo(50);
        assertThat(res.ordersThisMonth()).isEqualTo(20);
        assertThat(res.revenueThisMonth()).isEqualByComparingTo("8000.00");
        assertThat(res.delivered()).isEqualTo(16);
        assertThat(res.failed()).isEqualTo(6);
        // 16 delivered / 22 concluded = 72.7%
        assertThat(res.deliverySuccessRate()).isEqualTo(72.7);
        assertThat(res.topPerformerName()).isEqualTo("Anita");

        // Leads: 3 total, 2 won → 66.7%.
        assertThat(res.leadsTotal()).isEqualTo(3);
        assertThat(res.leadsWon()).isEqualTo(2);
        assertThat(res.leadConversionRate()).isEqualTo(66.7);

        // Source conversion: INSTAGRAM (1/1 = 100%) ranks above WHATSAPP (1/2 = 50%).
        assertThat(res.leadSources()).hasSize(2);
        assertThat(res.leadSources().get(0).source()).isEqualTo("INSTAGRAM");
        assertThat(res.leadSources().get(0).conversionRate()).isEqualTo(100.0);
        assertThat(res.leadSources().get(1).source()).isEqualTo("WHATSAPP");
        assertThat(res.leadSources().get(1).conversionRate()).isEqualTo(50.0);
        assertThat(res.topSource()).isEqualTo("INSTAGRAM");
    }

    @Test
    void teamLeadWithNoTeamGetsEmptyRollup() {
        when(userRepository.findIdsByTeamLeadId(5L)).thenReturn(List.of());

        TeamPerformanceService service =
                new TeamPerformanceService(stubPerf(List.of()), leadRepository, userRepository, scopeResolver);
        TeamPerformanceResponse res = service.forCaller(teamLead());

        assertThat(res.memberCount()).isZero();
        assertThat(res.ordersTotal()).isZero();
        assertThat(res.leaderboard()).isEmpty();
        assertThat(res.leadSources()).isEmpty();
        assertThat(res.topSource()).isNull();
        assertThat(res.topPerformerName()).isNull();
        assertThat(res.leadConversionRate()).isZero();
    }
}
