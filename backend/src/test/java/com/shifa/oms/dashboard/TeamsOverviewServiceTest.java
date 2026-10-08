package com.shifa.oms.dashboard;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse.TeamOverviewRow;
import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadRepository;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.performance.SalespersonPerformanceService;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@link TeamsOverviewService} — the "team-wise sales
 * with status" admin dashboard rollup ("Team Sameer" / "Team Zeeshan").
 */
class TeamsOverviewServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 9, 30).atStartOfDay(ZONE).toInstant(), ZONE);
    private static final LocalDate TODAY = LocalDate.now(CLOCK);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final LeadRepository leadRepository = mock(LeadRepository.class);
    // Recording subclass: Java 25 cannot mock the concrete SalespersonPerformanceService;
    // returns an empty leaderboard by default (individual tests do not assert on it).
    private final SalespersonPerformanceService performanceService = new SalespersonPerformanceService(
            mock(OrderRepository.class), mock(UserRepository.class), mock(LeadRepository.class)) {
        @Override
        public List<SalespersonPerformanceSummary> leaderboardFor(java.util.Collection<Long> memberIds) {
            return List.of();
        }
    };
    private final TeamsOverviewService service =
            new TeamsOverviewService(userRepository, orderRepository, leadRepository, performanceService, CLOCK);

    private static User user(long id, String name, Role role) {
        User u = new User("user" + id, "$2a$hash", role, name, true);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private static OrderEntity order(long createdBy, String status, String total, LocalDate date) {
        OrderEntity o = new OrderEntity("SHR-" + createdBy + "-" + date, OrderSource.SALESPERSON, createdBy,
                "Cust", "9800000000", "Addr", "City", "State", "411001");
        o.applyAmounts(new BigDecimal(total), BigDecimal.ZERO, new BigDecimal(total),
                BigDecimal.ZERO, PaymentStatus.COD);
        o.setOrderStatus(OrderStatus.valueOf(status));
        ReflectionTestUtils.setField(o, "createdAt", date.atTime(10, 0));
        return o;
    }

    private static LeadEntity lead(long ownerId, LeadStatus status, LocalDate followUp) {
        LeadEntity l = new LeadEntity("Lead " + ownerId, LeadSource.WHATSAPP, ownerId);
        l.setStatus(status);
        l.setCustomerMobile("9800000001");
        l.setFollowUpDate(followUp);
        return l;
    }

    @Test
    void rollsUpEachTeamsOrdersRevenueDeliveryAndLeadPipeline() {
        User sameer = user(10L, "Sameer", Role.TEAM_LEAD);
        User zeeshan = user(11L, "Zeeshan", Role.TEAM_LEAD);
        when(userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.TEAM_LEAD))
                .thenReturn(List.of(sameer, zeeshan));
        when(userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON))
                .thenReturn(List.of(user(1L, "Anita", Role.SALESPERSON), user(2L, "Rahul", Role.SALESPERSON)));
        when(userRepository.findIdsByTeamLeadId(10L)).thenReturn(List.of(1L));
        when(userRepository.findIdsByTeamLeadId(11L)).thenReturn(List.of(2L));

        // Orders are scoped to the member(s) PLUS the team lead's own id (the lead
        // punches orders too — their sales count towards the team total).
        when(orderRepository.findAllScopedIn(List.of(1L, 10L))).thenReturn(List.of(
                order(1L, "DELIVERED", "1000.00", TODAY),
                order(1L, "DELIVERY_FAILED", "500.00", TODAY.minusDays(40)),
                // Sameer's OWN order — must be included in the team's totals.
                order(10L, "DELIVERED", "700.00", TODAY)));
        when(orderRepository.findAllScopedIn(List.of(2L, 11L))).thenReturn(List.of(
                order(2L, "DELIVERED", "2000.00", TODAY)));

        // Leads stay scoped to the assigned salespeople only (a team lead owns no leads).
        when(leadRepository.findAllScopedIn(List.of(1L))).thenReturn(List.of(
                lead(1L, LeadStatus.NEW, TODAY.minusDays(3)),
                lead(1L, LeadStatus.WON, null)));
        when(leadRepository.findAllScopedIn(List.of(2L))).thenReturn(List.of(
                lead(2L, LeadStatus.QUOTED, TODAY.plusDays(2))));

        TeamsOverviewResponse res = service.overview();

        assertThat(res.asOf()).isEqualTo(TODAY);
        assertThat(res.teams()).hasSize(2);
        assertThat(res.unassigned()).isNull();

        TeamOverviewRow sameerRow = res.teams().stream()
                .filter(r -> "Sameer".equals(r.teamLeadName())).findFirst().orElseThrow();
        // memberCount is the assigned-salespeople team size (the lead is not a member),
        // but the ORDER totals now include the lead's own orders.
        assertThat(sameerRow.memberCount()).isEqualTo(1);
        assertThat(sameerRow.ordersTotal()).isEqualTo(3); // 2 member + 1 lead
        assertThat(sameerRow.ordersThisMonth()).isEqualTo(2); // member today + lead today
        assertThat(sameerRow.revenueTotal()).isEqualByComparingTo("2200.00"); // 1000 + 500 + 700
        assertThat(sameerRow.delivered()).isEqualTo(2); // member + lead delivered
        assertThat(sameerRow.failed()).isEqualTo(1);
        assertThat(sameerRow.deliverySuccessRate()).isEqualTo(66.7); // 2 / (2 + 1)
        assertThat(sameerRow.leadsTotal()).isEqualTo(2);
        assertThat(sameerRow.leadsWon()).isEqualTo(1);
        assertThat(sameerRow.leadConversionRate()).isEqualTo(50.0);
        // One overdue call-out (3 days), the WON lead is excluded.
        assertThat(sameerRow.callOuts()).hasSize(1);
        assertThat(sameerRow.callOuts().get(0).overdueDays()).isEqualTo(3);
        assertThat(sameerRow.callOuts().get(0).ownerName()).isEqualTo("Anita");

        TeamOverviewRow zeeshanRow = res.teams().stream()
                .filter(r -> "Zeeshan".equals(r.teamLeadName())).findFirst().orElseThrow();
        assertThat(zeeshanRow.ordersTotal()).isEqualTo(1);
        assertThat(zeeshanRow.deliverySuccessRate()).isEqualTo(100.0);
        // Follow-up is in the future — not yet a call-out.
        assertThat(zeeshanRow.callOuts()).isEmpty();
        assertThat(zeeshanRow.dueFollowUps()).isZero();
    }

    @Test
    void unassignedSalespeopleAreRolledIntoASeparateBucket() {
        when(userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.TEAM_LEAD)).thenReturn(List.of());
        when(userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON))
                .thenReturn(List.of(user(3L, "Solo", Role.SALESPERSON)));
        when(orderRepository.findAllScopedIn(List.of(3L))).thenReturn(List.of(
                order(3L, "DELIVERED", "300.00", TODAY)));
        when(leadRepository.findAllScopedIn(List.of(3L))).thenReturn(List.of());

        TeamsOverviewResponse res = service.overview();

        assertThat(res.teams()).isEmpty();
        assertThat(res.unassigned()).isNotNull();
        assertThat(res.unassigned().teamLeadName()).isEqualTo("Unassigned salespeople");
        assertThat(res.unassigned().memberCount()).isEqualTo(1);
        assertThat(res.unassigned().ordersTotal()).isEqualTo(1);
    }

    @Test
    void teamWithNoMembersAndNoLeadOrdersGetsAnEmptyRow() {
        User lead = user(20L, "EmptyLead", Role.TEAM_LEAD);
        when(userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.TEAM_LEAD)).thenReturn(List.of(lead));
        when(userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON)).thenReturn(List.of());
        when(userRepository.findIdsByTeamLeadId(20L)).thenReturn(List.of());
        // The lead's own order scope is still loaded (the lead can punch orders);
        // with none, the row is empty.
        when(orderRepository.findAllScopedIn(List.of(20L))).thenReturn(List.of());

        TeamsOverviewResponse res = service.overview();

        assertThat(res.teams()).hasSize(1);
        TeamOverviewRow row = res.teams().get(0);
        assertThat(row.memberCount()).isZero();
        assertThat(row.ordersTotal()).isZero();
        assertThat(row.deliverySuccessRate()).isNull();
        assertThat(row.leadConversionRate()).isNull();
        assertThat(row.callOuts()).isEmpty();
    }
}
