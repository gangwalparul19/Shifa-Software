package com.shifa.oms.statemachine;

import com.shifa.oms.auth.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Feature: shopify-quikshipx-order-sync, Property 18: courier-managed orders deny humans
 * and permit SYSTEM into managed stages, and Property 19: the context-aware authority
 * reduces to the pre-existing authority.
 *
 * <p>Property 19 is the important one. It is the machine-checked statement that adding the
 * context overloads changed nothing for any order that is not courier-managed — which is
 * every order in the system today. Without it, the only evidence that
 * {@code TransitionAuthorityPropertyTest} and the packing suites still hold would be that
 * they happen to pass.
 *
 * <p>Validates: Requirements 9.1, 9.2, 9.3, 9.10, 4.5, 4.7
 */
class ManagedOrderAuthorityPropertyTest {

    private final TransitionAuthority authority = new TransitionAuthority();

    // --- Property 19: reduction to the pre-existing behaviour ---------------

    @Property(tries = 1000)
    void anUnmanagedContextDecidesExactlyAsThePreExistingAuthority(
            @ForAll("statuses") OrderStatus from,
            @ForAll("statuses") OrderStatus to,
            @ForAll("roles") Role role,
            @ForAll boolean fallbackMode) {

        // Not courier-managed, internal channel — i.e. every order that predates this
        // feature, plus every order an admin has taken back into fallback mode.
        TransitionContext ctx = new TransitionContext(
                TransitionContext.ChannelView.INTERNAL, false, fallbackMode);

        assertThat(authority.permits(from, to, role, ctx))
                .as("human decision for %s -> %s as %s", from, to, role)
                .isEqualTo(authority.permits(from, to, role));

        assertThat(authority.permitsSystem(from, to, ctx))
                .as("system decision for %s -> %s", from, to)
                .isEqualTo(authority.permitsSystem(from, to));
    }

    @Property(tries = 1000)
    void theLegacyContextIsIndistinguishableFromNoContext(
            @ForAll("statuses") OrderStatus from,
            @ForAll("statuses") OrderStatus to,
            @ForAll("roles") Role role) {

        assertThat(authority.permits(from, to, role, TransitionContext.LEGACY))
                .isEqualTo(authority.permits(from, to, role));
        assertThat(authority.permitsSystem(from, to, TransitionContext.LEGACY))
                .isEqualTo(authority.permitsSystem(from, to));

        // A null context must behave as LEGACY rather than throwing, because every
        // pre-existing call site passes nothing.
        assertThat(authority.permits(from, to, role, null))
                .isEqualTo(authority.permits(from, to, role));
        assertThat(authority.permitsSystem(from, to, null))
                .isEqualTo(authority.permitsSystem(from, to));
    }

    @Property(tries = 500)
    void fallbackModeRestoresHumanAuthorityEvenWhenAShipmentExists(
            @ForAll("statuses") OrderStatus from,
            @ForAll("statuses") OrderStatus to,
            @ForAll("roles") Role role) {

        // A caller may hand in courierManaged=true together with fallbackMode=true; the
        // context normalises that contradiction so the authority never has to.
        TransitionContext takenBack = new TransitionContext(
                TransitionContext.ChannelView.INTERNAL, true, true);

        assertThat(takenBack.courierManaged()).isFalse();
        assertThat(authority.permits(from, to, role, takenBack))
                .isEqualTo(authority.permits(from, to, role));
    }

    // --- Property 18: managed orders deny humans ---------------------------

    @Property(tries = 1000)
    void aCourierManagedOrderDeniesEveryHumanRole(
            @ForAll("statuses") OrderStatus from,
            @ForAll("statuses") OrderStatus to,
            @ForAll("roles") Role role) {

        TransitionContext managed = new TransitionContext(
                TransitionContext.ChannelView.INTERNAL, true, false);

        // No role, not even ADMIN, and not even on an edge the table would normally
        // permit: the courier portal is the system of record for these stages.
        assertThat(authority.permits(from, to, role, managed)).isFalse();
    }

    @Property(tries = 500)
    void aCourierManagedOrderPermitsSystemIntoEveryManagedStage(
            @ForAll("statuses") OrderStatus from,
            @ForAll("statuses") OrderStatus to) {

        TransitionContext managed = new TransitionContext(
                TransitionContext.ChannelView.INTERNAL, true, false);

        if (ManagedStages.contains(to)) {
            assertThat(authority.permitsSystem(from, to, managed))
                    .as("SYSTEM into managed stage %s", to)
                    .isTrue();
        } else {
            // Outside the managed stages the pre-existing SYSTEM rules still govern, so
            // mirroring cannot invent a cancellation or an approval.
            assertThat(authority.permitsSystem(from, to, managed))
                    .isEqualTo(authority.permitsSystem(from, to));
        }
    }

    @Test
    void denialForAManagedOrderExplainsTheCourierPortalRatherThanTheRole() {
        TransitionContext managed = new TransitionContext(
                TransitionContext.ChannelView.INTERNAL, true, false);

        // The message a packer sees must point at the right place to act.
        assertThatThrownBy(() -> authority.assertAuthorized(
                OrderStatus.LABEL_GENERATED, OrderStatus.PACKED, Role.PACKING_USER, managed))
                .isInstanceOf(UnauthorizedTransitionException.class)
                .hasMessageContaining("courier portal");

        // An ordinary role denial keeps its original wording.
        assertThatThrownBy(() -> authority.assertAuthorized(
                OrderStatus.LABEL_GENERATED, OrderStatus.PACKED, Role.ACCOUNTANT,
                TransitionContext.LEGACY))
                .isInstanceOf(UnauthorizedTransitionException.class)
                .hasMessageContaining("not permitted");
    }

    // --- External-storefront auto-approval (Req 4.5 / 4.7) -----------------

    @Test
    void onlyAnExternalStorefrontOrderMayBeAutoApprovedBySystem() {
        TransitionContext external = new TransitionContext(
                TransitionContext.ChannelView.EXTERNAL_STOREFRONT, false, false);
        TransitionContext internal = TransitionContext.LEGACY;

        // Req 4.5: a storefront order arrives already committed, so an internal
        // approval gate would only stall a shipment the courier is already moving.
        assertThat(authority.permitsSystem(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED, external)).isTrue();

        // Req 4.7: an internally punched order must be approved by a human ADMIN.
        assertThat(authority.permitsSystem(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED, internal)).isFalse();

        // The grant is narrow: it does not let SYSTEM reject or cancel a storefront order.
        assertThat(authority.permitsSystem(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.REJECTED, external)).isFalse();
        assertThat(authority.permitsSystem(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.CANCELLED, external)).isFalse();

        assertThatCode(() -> authority.assertSystemAuthorized(
                OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED, external))
                .doesNotThrowAnyException();
    }

    @Test
    void adminApprovalIsUnaffectedByTheNewContext() {
        // The single most important existing behaviour: an ADMIN can still approve.
        assertThat(authority.permits(OrderStatus.PENDING_ADMIN_APPROVAL, OrderStatus.APPROVED,
                Role.ADMIN, TransitionContext.LEGACY)).isTrue();
        assertThat(authority.permits(OrderStatus.LABEL_GENERATED, OrderStatus.PACKED,
                Role.PACKING_USER, TransitionContext.LEGACY)).isTrue();
    }

    @Test
    void theManagedStageSetStartsAtLabelGeneratedAndExcludesThePreShipStages() {
        // Approval is an internal commercial decision; refusals to ship are too.
        assertThat(ManagedStages.contains(OrderStatus.PENDING_ADMIN_APPROVAL)).isFalse();
        assertThat(ManagedStages.contains(OrderStatus.APPROVED)).isFalse();
        assertThat(ManagedStages.contains(OrderStatus.REJECTED)).isFalse();
        assertThat(ManagedStages.contains(OrderStatus.CANCELLED)).isFalse();

        assertThat(ManagedStages.contains(OrderStatus.LABEL_GENERATED)).isTrue();
        assertThat(ManagedStages.contains(OrderStatus.DELIVERED)).isTrue();
        assertThat(ManagedStages.contains(OrderStatus.REDISPATCH)).isTrue();
    }

    @Provide
    Arbitrary<OrderStatus> statuses() {
        return Arbitraries.of(OrderStatus.values());
    }

    @Provide
    Arbitrary<Role> roles() {
        return Arbitraries.of(Role.values());
    }
}
