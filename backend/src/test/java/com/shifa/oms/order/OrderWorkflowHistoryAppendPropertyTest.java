package com.shifa.oms.order;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the central {@link OrderWorkflowService} history-append
 * invariant (design §Correctness Properties (5), §4.2, §4.3).
 *
 * Feature: role-based-order-workflow, Property 5: Each successful transition
 * appends exactly one history row (System source for courier-driven edges).
 *
 * **Validates: Requirements 12.6, 15.1, 15.5**
 *
 * <p>Pure and in-memory: exercises {@link OrderWorkflowService#applyTransition}
 * over every legal {@code (from, to)} edge of the design §4.1 table, driven by an
 * actor authorized for that edge (a permitted staff {@link Role}, or the
 * automatic {@code SYSTEM} actor for courier-driven edges). Each successful
 * transition must append <em>exactly one</em> {@link OrderStatusHistory} row that
 * records the correct from/to/actor/source, and courier-driven edges must be
 * recorded with the {@code SYSTEM} source (Req 15.5).
 *
 * <p>Per the Java 25 runtime gotcha, no concrete class is mocked: the "persistence"
 * being observed is the in-memory {@link OrderEntity} history collection, and the
 * audit collaborator is a small recording subclass of {@link AuditService}. Each
 * {@code @Property} runs the jqwik default of 1000 tries (≥ 100).
 */
class OrderWorkflowHistoryAppendPropertyTest {

    /** An authorized (edge, actor) case: apply {@code actor} across {@code from -> to}. */
    private record EdgeCase(OrderStatus from, OrderStatus to, Actor actor, boolean system) {
    }

    private static final List<EdgeCase> CASES = buildCases();

    // Feature: role-based-order-workflow, Property 5: Each successful transition appends exactly one history row
    // **Validates: Requirements 12.6, 15.1, 15.5**
    @Property
    void eachSuccessfulTransitionAppendsExactlyOneHistoryRow(@ForAll("cases") EdgeCase c) {
        AtomicInteger auditCount = new AtomicInteger();
        OrderWorkflowService workflow = new OrderWorkflowService(recordingAudit(auditCount));

        OrderEntity order = orderIn(c.from());
        int before = order.getStatusHistory().size();

        workflow.applyTransition(order, c.to(), c.actor());

        // Exactly one new status_history row was appended (Req 12.6, 15.1).
        assertThat(order.getStatusHistory()).hasSize(before + 1);
        OrderStatusHistory row = order.getStatusHistory().get(order.getStatusHistory().size() - 1);

        // The row records the transition faithfully.
        assertThat(row.getFromStatus()).isEqualTo(c.from());
        assertThat(row.getToStatus()).isEqualTo(c.to());
        assertThat(row.getActor()).isEqualTo(c.actor().name());
        assertThat(row.getSource()).isEqualTo(c.actor().source());

        // The aggregate advanced to the target status.
        assertThat(order.getOrderStatus()).isEqualTo(c.to());

        // Courier-driven edges are recorded as the automatic SYSTEM actor (Req 15.5).
        if (c.system()) {
            assertThat(c.actor().isSystem()).isTrue();
            assertThat(row.getSource()).isEqualTo("SYSTEM");
        }

        // Exactly one audit entry is written per transition.
        assertThat(auditCount.get()).isEqualTo(1);
    }

    @Provide
    Arbitrary<EdgeCase> cases() {
        return Arbitraries.of(CASES);
    }

    // --- Fixtures -----------------------------------------------------------

    /** All authorized (edge, actor) cases derived from the design §4.1 table. */
    private static List<EdgeCase> buildCases() {
        List<EdgeCase> cases = new ArrayList<>();
        addStaff(cases, OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED, Role.ADMIN);
        addStaff(cases, OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.REJECTED, Role.ADMIN);
        addStaff(cases, OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.CANCELLED, Role.ADMIN);
        // Auto label on approval — SYSTEM or ADMIN.
        addStaff(cases, OrderStatus.APPROVED, OrderStatus.LABEL_GENERATED, Role.ADMIN);
        addSystem(cases, OrderStatus.APPROVED, OrderStatus.LABEL_GENERATED);
        addStaff(cases, OrderStatus.LABEL_GENERATED, OrderStatus.PACKED, Role.PACKING_USER, Role.ADMIN);
        addStaff(cases, OrderStatus.PACKED, OrderStatus.HANDED_TO_DELIVERY, Role.PACKING_USER, Role.ADMIN);
        // Dispatch (staff) + assign (SYSTEM) share the edge; self-retain is SYSTEM.
        addStaff(cases, OrderStatus.HANDED_TO_DELIVERY, OrderStatus.COURIER_ASSIGNED,
                Role.PACKING_USER, Role.ADMIN);
        addSystem(cases, OrderStatus.HANDED_TO_DELIVERY, OrderStatus.COURIER_ASSIGNED);
        addSystem(cases, OrderStatus.HANDED_TO_DELIVERY, OrderStatus.HANDED_TO_DELIVERY);
        // Courier pickup + webhook progressions — SYSTEM only.
        addSystem(cases, OrderStatus.COURIER_ASSIGNED, OrderStatus.DISPATCHED);
        addSystem(cases, OrderStatus.DISPATCHED, OrderStatus.IN_TRANSIT);
        addSystem(cases, OrderStatus.DISPATCHED, OrderStatus.OUT_FOR_DELIVERY);
        addSystem(cases, OrderStatus.DISPATCHED, OrderStatus.RTO);
        addSystem(cases, OrderStatus.DISPATCHED, OrderStatus.REDISPATCH);
        addSystem(cases, OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY);
        addSystem(cases, OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED);
        addSystem(cases, OrderStatus.IN_TRANSIT, OrderStatus.RTO);
        addSystem(cases, OrderStatus.IN_TRANSIT, OrderStatus.REDISPATCH);
        addSystem(cases, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED);
        addSystem(cases, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CUSTOMER_REJECTED);
        addSystem(cases, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERY_FAILED);
        addSystem(cases, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.RTO);
        addSystem(cases, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.REDISPATCH);
        // Settlement — ACCOUNTANT/ADMIN or SYSTEM.
        addStaff(cases, OrderStatus.DELIVERED, OrderStatus.CLOSED, Role.ACCOUNTANT, Role.ADMIN);
        addStaff(cases, OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, Role.ACCOUNTANT, Role.ADMIN);
        addSystem(cases, OrderStatus.DELIVERED, OrderStatus.CLOSED);
        addSystem(cases, OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED);
        return cases;
    }

    private static void addStaff(List<EdgeCase> cases, OrderStatus from, OrderStatus to, Role... roles) {
        for (Role role : roles) {
            cases.add(new EdgeCase(from, to,
                    Actor.user(role.name().toLowerCase() + "-user", role, role.name()), false));
        }
    }

    private static void addSystem(List<EdgeCase> cases, OrderStatus from, OrderStatus to) {
        cases.add(new EdgeCase(from, to, Actor.system("COURIER_API", "SYSTEM"), true));
    }

    private static OrderEntity orderIn(OrderStatus status) {
        OrderEntity order = new OrderEntity(
                "SHR-000123", OrderSource.STOREFRONT, 7L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(new BigDecimal("240.00"), BigDecimal.ZERO,
                new BigDecimal("240.00"), new BigDecimal("240.00"), PaymentStatus.COD);
        order.setOrderStatus(status);
        return order;
    }

    /**
     * A recording subclass of the concrete {@link AuditService} that counts audit
     * writes without touching a database — the project's prescribed alternative to
     * mocking a concrete class on the Java 25 runtime.
     */
    private static AuditService recordingAudit(AtomicInteger counter) {
        return new AuditService(null, new CurrentUserService()) {
            @Override
            public AuditEvent record(String action, String entityType, String entityId, String summary) {
                counter.incrementAndGet();
                return null;
            }

            @Override
            public AuditEvent record(Long actorUserId, String actorUsername, String action,
                                     String entityType, String entityId, String summary) {
                counter.incrementAndGet();
                return null;
            }
        };
    }
}
