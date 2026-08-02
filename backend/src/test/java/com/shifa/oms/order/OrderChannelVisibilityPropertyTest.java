package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.order.dto.OrderSummaryResponse;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: shopify-quikshipx-order-sync, Property 22: the channel is total, correct per
 * creation path and exposed; Property 23: a channel change is rejected atomically; and
 * Property 25's role rule: channel visibility follows the caller's role.
 *
 * <p>Property 23 is enforced <em>structurally</em> rather than by a runtime check, and the
 * test says so: {@link OrderEntity} deliberately has no channel mutator, so "an update
 * cannot change the channel" is a compile-time fact instead of a validation rule someone can
 * forget to call. The test guards against a future setter quietly reintroducing the risk.
 *
 * <p>Validates: Requirements 1.1&ndash;1.6, 11.6, 11.7
 */
class OrderChannelVisibilityPropertyTest {

    // --- Property 22: exposure -----------------------------------------------

    @Property(tries = 200)
    void theListResponseReturnsTheStoredChannelUnchanged(@ForAll OrderSource source) {
        OrderEntity order = order(source);

        OrderSummaryResponse response = OrderSummaryResponse.from(order);

        // Returned as stored (Req 1.5). Folding SALESPERSON/STOREFRONT is the presentation
        // layer's job; the API stays an honest report of the row.
        assertThat(response.channel()).isEqualTo(source);
        assertThat(response.channel().canonical())
                .isIn(OrderSource.SHOPIFY_API, OrderSource.SHIFA_ADMIN);
    }

    @Test
    void aShopifyOrderCarriesItsShopifyIdentifiers() {
        OrderEntity order = order(OrderSource.SHOPIFY_API);
        order.setShopifyIdentifiers("1042", "#1042");

        assertThat(order.getShopifyOrderId()).isEqualTo("1042");
        assertThat(order.getShopifyOrderNumber()).isEqualTo("#1042");
        assertThat(order.getSource().isShopify()).isTrue();
    }

    // --- Property 23: the channel cannot be changed --------------------------

    @Test
    void orderEntityExposesNoChannelMutator() {
        boolean hasSetter = Arrays.stream(OrderEntity.class.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals("setSource") || name.equals("setChannel"));

        // Immutability by construction: there is no code path to reject, because there is
        // no code path at all. Adding a setter would silently make Req 1.4 a runtime
        // concern, so this assertion is the guard.
        assertThat(hasSetter)
                .as("OrderEntity must not expose a channel mutator (Req 1.4)")
                .isFalse();
    }

    // --- Property 25: visibility follows the caller's role -------------------

    @Property(tries = 200)
    void salespeopleAndTeamLeadsAreAlwaysPinnedToTheShifaChannel(
            @ForAll Role role,
            @ForAll OrderSource requested) {

        OrderSource effective = AdminOrderController.effectiveChannel(principal(role), requested);

        boolean shifaOnly = role == Role.SALESPERSON || role == Role.TEAM_LEAD;
        if (shifaOnly) {
            // Whatever they ask for — including an explicit ?channel=SHOPIFY_API — they get
            // Shifa's own orders only (Req 11.6, 11.7).
            assertThat(effective).isEqualTo(OrderSource.SHIFA_ADMIN);
        } else {
            // Every other role sees exactly what it asked for, including "both channels".
            assertThat(effective).isEqualTo(requested);
        }
    }

    @Test
    void anAdminAskingForNothingSeesBothChannels() {
        assertThat(AdminOrderController.effectiveChannel(principal(Role.ADMIN), null)).isNull();
        assertThat(AdminOrderController.effectiveChannel(principal(Role.ACCOUNTANT), null)).isNull();
        // A salesperson asking for nothing is still pinned, so the confidentiality rule does
        // not depend on the client sending a filter.
        assertThat(AdminOrderController.effectiveChannel(principal(Role.SALESPERSON), null))
                .isEqualTo(OrderSource.SHIFA_ADMIN);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static OrderEntity order(OrderSource source) {
        OrderEntity order = new OrderEntity("SHR-20260801-AAAA", source, 5L,
                "Asha Kumar", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.setOrderStatus(OrderStatus.INITIAL);
        order.applyAmounts(new BigDecimal("500.00"), BigDecimal.ZERO, new BigDecimal("500.00"),
                new BigDecimal("500.00"), com.shifa.oms.order.domain.PaymentStatus.COD);
        return order;
    }

    private static AuthPrincipal principal(Role role) {
        return new AuthPrincipal(7L, "user", role);
    }
}
