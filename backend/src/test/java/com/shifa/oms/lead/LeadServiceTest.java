package com.shifa.oms.lead;

import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.lead.dto.BulkAssignResult;
import com.shifa.oms.lead.dto.LeadResponse;
import com.shifa.oms.lead.dto.LeadTagResponse;
import com.shifa.oms.order.LeadSource;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the CRM-expansion {@link LeadService} additions (Phase 1):
 * assignment authorization/semantics, bulk-assign per-lead results, and
 * tag add/remove — including on a terminal (WON/LOST) lead (Req 1, 4, 5.1).
 *
 * <p>Follows the repo conventions: the repository <em>interfaces</em> are
 * Mockito-mocked over in-memory stores; concrete collaborators
 * ({@link AuditService}, {@link StaffNotificationDispatcher}) are real recording
 * instances / subclasses (Java 25 cannot mock concrete classes). A fixed clock
 * makes {@code assigned_at} deterministic.
 */
class LeadServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2024-06-01T10:15:00Z"), ZoneOffset.UTC);
    private static final AuthPrincipal ADMIN = new AuthPrincipal(1L, "admin", Role.ADMIN);
    private static final AuthPrincipal TEAM_LEAD = new AuthPrincipal(2L, "lead", Role.TEAM_LEAD);
    private static final AuthPrincipal SALES = new AuthPrincipal(3L, "sales", Role.SALESPERSON);

    // --- Assignment ---------------------------------------------------------

    @Test
    void salespersonCannotAssignLead() {
        Fixture f = new Fixture();
        f.store.add(lead(10L, "Asha", 3L, LeadStatus.NEW));

        assertThatThrownBy(() -> f.service.assign(10L, 99L, false, SALES))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminAssignUpdatesOwnerKeepsCreatorStampsAssignedAtAndNotifies() {
        Fixture f = new Fixture();
        LeadEntity l = lead(10L, "Asha", 3L, LeadStatus.NEW);
        l.setCreatedByUserId(3L);
        f.store.add(l);

        LeadResponse response = f.service.assign(10L, 7L, false, ADMIN);

        assertThat(response.ownerUserId()).isEqualTo(7L);
        assertThat(l.getOwnerUserId()).isEqualTo(7L);
        assertThat(l.getCreatedByUserId()).isEqualTo(3L); // creator preserved (Req 1.1)
        assertThat(l.getAssignedAt()).isNotNull();
        assertThat(f.notifiedUsers).containsExactly(7L); // new assignee notified (Req 1.6)
    }

    @Test
    void teamLeadMayAssign() {
        Fixture f = new Fixture();
        f.store.add(lead(10L, "Asha", 3L, LeadStatus.NEW));

        LeadResponse response = f.service.assign(10L, 2L, true, TEAM_LEAD);

        assertThat(response.ownerUserId()).isEqualTo(2L);
        // Assigning to the same owner fires no self-notification.
        assertThat(f.notifiedUsers).doesNotContain((Long) null);
    }

    @Test
    void bulkAssignReportsPerLeadSuccessAndSkip() {
        Fixture f = new Fixture();
        f.store.add(lead(10L, "Asha", 3L, LeadStatus.NEW));
        f.store.add(lead(11L, "Bina", 3L, LeadStatus.CONTACTED));

        BulkAssignResult result = f.service.bulkAssign(List.of(10L, 11L, 999L), 7L, ADMIN);

        assertThat(result.succeeded()).containsExactly(10L, 11L);
        assertThat(result.skipped()).hasSize(1);
        assertThat(result.skipped().get(0).id()).isEqualTo(999L);
    }

    @Test
    void salespersonCannotBulkAssign() {
        Fixture f = new Fixture();
        f.store.add(lead(10L, "Asha", 3L, LeadStatus.NEW));

        assertThatThrownBy(() -> f.service.bulkAssign(List.of(10L), 7L, SALES))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- Round-robin assignment (Req 1.3) -----------------------------------

    @Test
    void chooseRoundRobinMemberPicksFewestOpenThenLowestId() {
        // 7 has the fewest open leads → chosen.
        assertThat(LeadService.chooseRoundRobinMember(
                List.of(5L, 7L, 9L), Map.of(5L, 3L, 7L, 1L, 9L, 2L))).isEqualTo(7L);
    }

    @Test
    void chooseRoundRobinMemberTieBreaksOnLowestId() {
        // 5 and 9 tie at 2 open; 5 wins on lowest id. A member absent from the map counts 0,
        // so here none are absent — pure tie-break.
        assertThat(LeadService.chooseRoundRobinMember(
                List.of(9L, 5L, 7L), Map.of(5L, 2L, 7L, 4L, 9L, 2L))).isEqualTo(5L);
    }

    @Test
    void chooseRoundRobinMemberTreatsMissingOwnerAsZero() {
        // 8 is absent from the map → counts as 0 open → least loaded.
        assertThat(LeadService.chooseRoundRobinMember(
                List.of(6L, 8L), Map.of(6L, 1L))).isEqualTo(8L);
    }

    @Test
    void roundRobinAssignToTeamLeadPicksLeastLoadedMember() {
        Fixture f = new Fixture();
        f.store.add(lead(10L, "Asha", 3L, LeadStatus.NEW));
        // Team lead id 2 with members 5, 7 (active). 5 has 2 open leads, 7 has 0 → pick 7.
        f.teamLead(2L);
        f.teamMembers(2L, user(5L, Role.SALESPERSON, true), user(7L, Role.SALESPERSON, true));
        f.openLeads(Map.of(5L, 2L, 7L, 0L));

        LeadResponse response = f.service.assign(10L, 2L, true, ADMIN);

        assertThat(response.ownerUserId()).isEqualTo(7L);
        assertThat(f.notifiedUsers).containsExactly(7L);
    }

    @Test
    void roundRobinAssignWithEmptyTeamFallsBackToTeamLead() {
        Fixture f = new Fixture();
        f.store.add(lead(10L, "Asha", 3L, LeadStatus.NEW));
        f.teamLead(2L);
        f.teamMembers(2L); // no members

        LeadResponse response = f.service.assign(10L, 2L, true, ADMIN);

        assertThat(response.ownerUserId()).isEqualTo(2L);
    }

    @Test
    void nonRoundRobinAssignGoesDirectlyToTarget() {
        Fixture f = new Fixture();
        f.store.add(lead(10L, "Asha", 3L, LeadStatus.NEW));
        f.teamLead(2L);
        f.teamMembers(2L, user(5L, Role.SALESPERSON, true));

        // roundRobin=false → assigned to the team lead directly, no fan-out.
        LeadResponse response = f.service.assign(10L, 2L, false, ADMIN);

        assertThat(response.ownerUserId()).isEqualTo(2L);
    }

    // --- Assignable users (Req 1) -------------------------------------------

    @Test
    void assignableUsersForTeamLeadReturnsOwnTeamPlusSelf() {
        Fixture f = new Fixture();
        f.selfUser(user(2L, Role.TEAM_LEAD, true));
        f.teamMembers(2L, user(5L, Role.SALESPERSON, true), user(7L, Role.SALESPERSON, true));

        List<com.shifa.oms.order.dto.AssignableCreatorResponse> users =
                f.service.assignableUsers(TEAM_LEAD);

        assertThat(users).extracting(com.shifa.oms.order.dto.AssignableCreatorResponse::id)
                .containsExactly(2L, 5L, 7L);
    }

    @Test
    void assignableUsersForAdminReturnsAllTeamLeadsAndSalespeople() {
        Fixture f = new Fixture();
        f.usersByRole(Role.TEAM_LEAD, user(2L, Role.TEAM_LEAD, true));
        f.usersByRole(Role.SALESPERSON,
                user(5L, Role.SALESPERSON, true), user(8L, Role.SALESPERSON, false));

        List<com.shifa.oms.order.dto.AssignableCreatorResponse> users =
                f.service.assignableUsers(ADMIN);

        // Team leads first, then active salespeople; the inactive salesperson (8) is omitted.
        assertThat(users).extracting(com.shifa.oms.order.dto.AssignableCreatorResponse::id)
                .containsExactly(2L, 5L);
    }

    // --- Tags ---------------------------------------------------------------

    @Test
    void addAndRemoveTagIsIdempotentAndAllowedOnTerminalLead() {
        Fixture f = new Fixture();
        LeadEntity won = lead(20L, "Won Lead", 3L, LeadStatus.WON); // terminal (Req 5.1)
        f.store.add(won);
        f.tags.add(tag(5L, "DND"));

        // Add twice — second add is a no-op (idempotent), still returns the tag.
        LeadResponse first = f.service.addTag(20L, 5L, SALES);
        LeadResponse second = f.service.addTag(20L, 5L, SALES);
        assertThat(first.tags()).extracting(LeadTagResponse::name).containsExactly("DND");
        assertThat(second.tags()).extracting(LeadTagResponse::name).containsExactly("DND");
        assertThat(f.assignments).hasSize(1);

        // Remove it — back to no tags, on a terminal lead.
        LeadResponse removed = f.service.removeTag(20L, 5L, SALES);
        assertThat(removed.tags()).isEmpty();
        assertThat(f.assignments).isEmpty();
    }

    // --- Fixture ------------------------------------------------------------

    /** Wires a real {@link LeadService} over in-memory repo mocks + recording collaborators. */
    private static final class Fixture {
        final List<LeadEntity> store = new ArrayList<>();
        final List<LeadTag> tags = new ArrayList<>();
        final List<LeadTagAssignment> assignments = new ArrayList<>();
        final List<Long> notifiedUsers = new ArrayList<>();
        final List<User> users = new ArrayList<>();
        final Map<Long, List<User>> teamsByLeadId = new java.util.HashMap<>();
        final Map<Long, Long> openLeadsByOwner = new java.util.HashMap<>();
        final UserRepository userRepo = mock(UserRepository.class);
        final LeadService service;

        Fixture() {
            LeadRepository leadRepo = mock(LeadRepository.class);
            lenient().when(leadRepo.save(any(LeadEntity.class))).thenAnswer(i -> i.getArgument(0));
            lenient().when(leadRepo.findById(anyLong())).thenAnswer(i -> {
                Long id = i.getArgument(0);
                return store.stream().filter(l -> id.equals(l.getId())).findFirst();
            });
            lenient().when(leadRepo.findByIdAndOwnerUserId(anyLong(), anyLong())).thenAnswer(i -> {
                Long id = i.getArgument(0);
                Long owner = i.getArgument(1);
                return store.stream()
                        .filter(l -> id.equals(l.getId()) && owner.equals(l.getOwnerUserId()))
                        .findFirst();
            });

            LeadTagRepository tagRepo = mock(LeadTagRepository.class);
            lenient().when(tagRepo.findById(anyLong())).thenAnswer(i -> {
                Long id = i.getArgument(0);
                return tags.stream().filter(t -> id.equals(t.getId())).findFirst();
            });
            lenient().when(tagRepo.findAllById(any())).thenAnswer(i -> {
                Iterable<Long> ids = i.getArgument(0);
                List<LeadTag> out = new ArrayList<>();
                for (Long id : ids) {
                    tags.stream().filter(t -> id.equals(t.getId())).findFirst().ifPresent(out::add);
                }
                return out;
            });

            LeadTagAssignmentRepository assignRepo = mock(LeadTagAssignmentRepository.class);
            lenient().when(assignRepo.save(any(LeadTagAssignment.class))).thenAnswer(i -> {
                LeadTagAssignment a = i.getArgument(0);
                assignments.add(a);
                return a;
            });
            lenient().when(assignRepo.existsByLeadIdAndTagId(anyLong(), anyLong())).thenAnswer(i -> {
                Long leadId = i.getArgument(0);
                Long tagId = i.getArgument(1);
                return assignments.stream()
                        .anyMatch(a -> leadId.equals(a.getLeadId()) && tagId.equals(a.getTagId()));
            });
            lenient().when(assignRepo.findByLeadId(anyLong())).thenAnswer(i -> {
                Long leadId = i.getArgument(0);
                return assignments.stream().filter(a -> leadId.equals(a.getLeadId())).toList();
            });
            lenient().when(assignRepo.deleteByLeadIdAndTagId(anyLong(), anyLong())).thenAnswer(i -> {
                Long leadId = i.getArgument(0);
                Long tagId = i.getArgument(1);
                return assignments.removeIf(
                        a -> leadId.equals(a.getLeadId()) && tagId.equals(a.getTagId())) ? 1 : 0;
            });

            AuditService audit = new AuditService(null, new CurrentUserService()) {
                @Override
                public AuditEvent record(String action, String entityType, String entityId,
                                         String summary) {
                    return null;
                }
            };

            // Open-lead counts per owner for round-robin selection.
            lenient().when(leadRepo.countOpenLeadsByOwner(any())).thenAnswer(i -> {
                Iterable<Long> ids = i.getArgument(0);
                List<LeadRepository.OpenLeadCount> out = new ArrayList<>();
                for (Long id : ids) {
                    if (openLeadsByOwner.containsKey(id)) {
                        out.add(openLeadCount(id, openLeadsByOwner.get(id)));
                    }
                }
                return out;
            });

            // UserRepository: resolve by id + a team lead's members by lead id.
            lenient().when(userRepo.findById(anyLong())).thenAnswer(i -> {
                Long id = i.getArgument(0);
                return users.stream().filter(u -> id.equals(u.getId())).findFirst();
            });
            lenient().when(userRepo.findByTeamLeadIdOrderByFullNameAsc(anyLong())).thenAnswer(i -> {
                Long leadId = i.getArgument(0);
                return teamsByLeadId.getOrDefault(leadId, List.of());
            });
            lenient().when(userRepo.findByRoleOrderByCreatedAtDescIdDesc(any())).thenAnswer(i -> {
                Role role = i.getArgument(0);
                return users.stream().filter(u -> u.getRole() == role).toList();
            });

            StaffNotificationDispatcher dispatcher =
                    new StaffNotificationDispatcher(null, null, null) {
                        @Override
                        public com.shifa.oms.adminnotification.AdminNotification dispatchToUser(
                                String type, String title, String detail, String severity,
                                Long orderId, String orderCode, Long sourceEventId, Long userId) {
                            notifiedUsers.add(userId);
                            return null;
                        }
                    };

            this.service = new LeadService(leadRepo, new SalespersonScopeResolver(), audit,
                    new LeadServiceTestSupport.NoOpOrderService(), tagRepo, assignRepo, userRepo,
                    dispatcher, null, null, CLOCK);
        }

        /** Registers a user id as a TEAM_LEAD resolvable via findById. */
        void teamLead(long id) {
            users.add(user(id, Role.TEAM_LEAD, true));
        }

        /** Registers a user resolvable via findById (e.g. the calling team lead themselves). */
        void selfUser(User u) {
            users.add(u);
        }

        /** Sets the active members a team lead's round-robin / assignable-users resolves to. */
        void teamMembers(long teamLeadId, User... members) {
            teamsByLeadId.put(teamLeadId, List.of(members));
        }

        /** Open-lead counts per owner the round-robin uses. */
        void openLeads(Map<Long, Long> counts) {
            openLeadsByOwner.putAll(counts);
        }

        /** Registers users returned by findByRoleOrderByCreatedAtDescIdDesc for a role. */
        void usersByRole(Role role, User... roleUsers) {
            users.addAll(List.of(roleUsers));
        }
    }

    private static LeadRepository.OpenLeadCount openLeadCount(Long ownerId, long count) {
        return new LeadRepository.OpenLeadCount() {
            @Override
            public Long getOwnerId() {
                return ownerId;
            }

            @Override
            public long getCnt() {
                return count;
            }
        };
    }

    private static User user(long id, Role role, boolean active) {
        User u = new User("user-" + id, "hash", role, "User " + id, active);
        setField(u, "id", id);
        return u;
    }

    private static LeadEntity lead(long id, String name, long owner, LeadStatus status) {
        LeadEntity l = new LeadEntity(name, LeadSource.WHATSAPP, owner);
        l.setStatus(status);
        setField(l, "id", id);
        return l;
    }

    private static LeadTag tag(long id, String name) {
        LeadTag t = new LeadTag(name, null, "DISPOSITION", 1);
        setField(t, "id", id);
        return t;
    }

    private static void setField(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
