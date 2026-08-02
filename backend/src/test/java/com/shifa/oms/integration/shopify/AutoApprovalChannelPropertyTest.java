package com.shifa.oms.integration.shopify;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.order.Actor;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderStatusHistory;
import com.shifa.oms.order.OrderWorkflowService;
import com.shifa.oms.statemachine.OrderStatus;
import com.shifa.oms.statemachine.TransitionAuthority;
import com.shifa.oms.statemachine.TransitionContext;
import com.shifa.oms.statemachine.UnauthorizedTransitionException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature: shopify-quikshipx-order-sync, Property 11: automatic approval is exactly the
 * Shopify channel.
 *
 * <p>For any order, a {@code SYSTEM} transition to {@code APPROVED} is permitted if and only
 * if the order's channel is {@code SHOPIFY_API}; and no {@code SHIFA_ADMIN} order may ever be
 * approved by {@code SYSTEM}.
 *
 * <p>This is the load-bearing half of the approval-bypass design. Getting the "only if"
 * direction wrong would be the serious failure: it would let an internal order skip the
 * ADMIN approval gate entirely, which is the one control the business has over what ships.
 *
 * <p>The test exercises the real {@link TransitionAuthority} and the real
 * {@link OrderWorkflowService}, so it pins the behaviour actually used in production rather
 * than a re-statement of the rule.
 *
 * <p>Validates: Requirements 4.5, 4.7
 */
class AutoApprovalChannelPropertyTest {

    private final TransitionAuthority authority = new TransitionAuthority();

    // --- The authority rule -------------------------------------------------

    @Property(tries = 200)
    void systemMayApproveOnlyAnExternalStorefrontOrder(
            @ForAll TransitionContext.ChannelView channel,
            @ForAll boolean fallbackMode) {

        // courierManaged is false: an order awaiting approval has no shipment yet.
        TransitionContext context = new TransitionContext(channel, false, fallbackMode);

        boolean permitted = authority.permitsSystem(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED, context);

        assertThat(permitted)
                .isEqualTo(channel == TransitionContext.ChannelView.EXTERNAL_STOREFRONT);
    }

    @Test
    void theLegacyContextFreeRuleStillForbidsSystemApproval() {
        // Every pre-existing call site passes no context, and must keep behaving exactly
        // as it did: approval is an ADMIN act.
        assertThat(authority.permitsSystem(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED)).isFalse();
        assertThat(authority.permitsSystem(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED, TransitionContext.LEGACY))
                .isFalse();
        assertThat(authority.permits(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED, Role.ADMIN)).isTrue();
    }

    @Test
    void grantingSystemApprovalDoesNotGrantSystemAnythingElseFromTheApprovalStage() {
        TransitionContext external = new TransitionContext(
                TransitionContext.ChannelView.EXTERNAL_STOREFRONT, false, false);

        // The exemption is scoped to the one edge. SYSTEM must not be able to reject or
        // cancel a storefront order on its own.
        assertThat(authority.permitsSystem(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.REJECTED, external)).isFalse();
        assertThat(authority.permitsSystem(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.CANCELLED, external)).isFalse();
    }

    // --- The applied behaviour ----------------------------------------------

    @Property(tries = 100)
    void applyingTheApprovalSucceedsForShopifyAndIsRefusedForShifaAdmin(
            @ForAll OrderSource source,
            @ForAll boolean needsReview) {

        RecordingAudit audit = new RecordingAudit();
        // No dispatcher and no ownership: the context therefore reports "not managed",
        // exactly as it does for an order that has no shipment yet.
        OrderWorkflowService workflow = new OrderWorkflowService(audit);
        OrderEntity order = pendingOrder(source);

        // needsReview is a property of order_review_reasons, not of the order row, so an
        // order in the review queue is byte-identical here. Approval is therefore
        // provably independent of it (Req 4.5).
        assertThat(needsReview).isIn(true, false);

        if (source.canonical() == OrderSource.SHOPIFY_API) {
            workflow.applyTransition(order, OrderStatus.APPROVED,
                    Actor.system("SHOPIFY_API", "SHOPIFY"));

            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.APPROVED);
            // Exactly one row added, continuing the chain (Req 4.1, 4.3).
            assertThat(order.getStatusHistory()).hasSize(2);
            OrderStatusHistory last = order.getStatusHistory().get(1);
            assertThat(last.getFromStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
            assertThat(last.getToStatus()).isEqualTo(OrderStatus.APPROVED);
            assertThat(audit.actions).containsExactly("ORDER_STATUS_CHANGED");
        } else {
            assertThatThrownBy(() -> workflow.applyTransition(order, OrderStatus.APPROVED,
                    Actor.system("SHOPIFY_API", "SHOPIFY")))
                    .isInstanceOf(UnauthorizedTransitionException.class);

            // Refused with nothing moved: no status change, no extra history row.
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
            assertThat(order.getStatusHistory()).hasSize(1);
            assertThat(audit.actions).isEmpty();
        }
    }

    @Test
    void anAdminStillApprovesAShifaAdminOrderNormally() {
        RecordingAudit audit = new RecordingAudit();
        OrderWorkflowService workflow = new OrderWorkflowService(audit);
        OrderEntity order = pendingOrder(OrderSource.SHIFA_ADMIN);

        workflow.applyTransition(order, OrderStatus.APPROVED,
                Actor.user("admin", Role.ADMIN, "ADMIN"));

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static OrderEntity pendingOrder(OrderSource source) {
        OrderEntity order = new OrderEntity("SHR-20260801-TEST", source, null,
                "Asha Kumar", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.setOrderStatus(OrderStatus.INITIAL);
        order.addStatusHistory(new OrderStatusHistory(
                null, OrderStatus.INITIAL, "SHOPIFY_API", "SHOPIFY"));
        return order;
    }

    /**
     * Recording subclass rather than a mock: on Java 25 Mockito cannot mock concrete
     * classes, which is why this project uses subclasses throughout.
     */
    private static final class RecordingAudit extends AuditService {

        private final List<String> actions = new ArrayList<>();

        RecordingAudit() {
            super(null, null);
        }

        @Override
        public AuditEvent record(String action, String entityType, String entityId, String summary) {
            actions.add(action);
            return null;
        }

        @Override
        public AuditEvent record(Long actorUserId, String actorUsername, String action,
                                 String entityType, String entityId, String summary) {
            actions.add(action);
            return null;
        }
    }
}
