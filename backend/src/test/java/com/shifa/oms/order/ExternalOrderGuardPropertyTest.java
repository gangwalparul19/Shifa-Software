package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ExternalOrderReadOnlyException;
import com.shifa.oms.order.domain.PaymentStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature: shopify-quikshipx-order-sync, Property 20: Shopify-origin orders are read-only.
 *
 * <p>For any order whose channel is {@code SHOPIFY_API}, any request that would modify its
 * content is rejected, and any cancellation from an actor other than SYSTEM is rejected,
 * with every stored field left unchanged. For any other channel the guard is transparent.
 *
 * <p>Validates: Requirements 9.4, 9.5
 */
class ExternalOrderGuardPropertyTest {

    @Property(tries = 500)
    void contentIsMutableForEveryChannelExceptShopify(@ForAll("channels") OrderSource channel) {
        OrderEntity order = order(channel);

        if (channel.isShopify()) {
            assertThatThrownBy(() -> ExternalOrderGuard.requireContentMutable(order))
                    .isInstanceOf(ExternalOrderReadOnlyException.class)
                    // The message must say where to make the change instead.
                    .hasMessageContaining("Shopify");
        } else {
            assertThatCode(() -> ExternalOrderGuard.requireContentMutable(order))
                    .doesNotThrowAnyException();
        }
    }

    @Property(tries = 500)
    void onlySystemMayCancelAShopifyOrder(
            @ForAll("channels") OrderSource channel,
            @ForAll("roles") Role role) {

        OrderEntity order = order(channel);
        Actor human = Actor.user(new AuthPrincipal(1L, "someone", role), role.name());

        if (channel.isShopify()) {
            // No human role, not even ADMIN: refusing here would leave Shopify believing
            // the order is live, with no refund and no customer email.
            assertThatThrownBy(() -> ExternalOrderGuard.requireCancellable(order, human))
                    .isInstanceOf(ExternalOrderReadOnlyException.class);
            // A cancellation mirrored back FROM the storefront is exactly how it should
            // reach Shifa, so SYSTEM is permitted.
            assertThatCode(() -> ExternalOrderGuard.requireCancellable(
                    order, Actor.system("SHOPIFY", "SHOPIFY")))
                    .doesNotThrowAnyException();
        } else {
            assertThatCode(() -> ExternalOrderGuard.requireCancellable(order, human))
                    .doesNotThrowAnyException();
            assertThatCode(() -> ExternalOrderGuard.requireCancellable(
                    order, Actor.system("SHOPIFY", "SHOPIFY")))
                    .doesNotThrowAnyException();
        }
    }

    @Property(tries = 500)
    void arejectedGuardLeavesEveryStoredFieldUnchanged(@ForAll("roles") Role role) {
        OrderEntity order = order(OrderSource.SHOPIFY_API);

        String name = order.getCustomerName();
        String mobile = order.getCustomerMobile();
        String address = order.getAddressLine();
        BigDecimal total = order.getTotalAmount();
        OrderSource source = order.getSource();

        assertThatThrownBy(() -> ExternalOrderGuard.requireContentMutable(order))
                .isInstanceOf(ExternalOrderReadOnlyException.class);
        assertThatThrownBy(() -> ExternalOrderGuard.requireCancellable(order,
                Actor.user(new AuthPrincipal(1L, "someone", role), role.name())))
                .isInstanceOf(ExternalOrderReadOnlyException.class);

        // A guard is a pure check: nothing about the order may move.
        assertThat(order.getCustomerName()).isEqualTo(name);
        assertThat(order.getCustomerMobile()).isEqualTo(mobile);
        assertThat(order.getAddressLine()).isEqualTo(address);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(total);
        assertThat(order.getSource()).isEqualTo(source);
    }

    @Test
    void aNullActorIsTreatedAsAHumanAndDenied() {
        // Fail closed: an unattributed cancellation must not slip through as SYSTEM.
        assertThatThrownBy(() -> ExternalOrderGuard.requireCancellable(
                order(OrderSource.SHOPIFY_API), null))
                .isInstanceOf(ExternalOrderReadOnlyException.class);
    }

    @Test
    void legacyChannelsAreNotTreatedAsExternallyOwned() {
        // STOREFRONT is the removed public checkout, not Shopify. Those orders were ours,
        // so admins must keep full control of them.
        assertThat(ExternalOrderGuard.isExternallyOwned(order(OrderSource.STOREFRONT))).isFalse();
        assertThat(ExternalOrderGuard.isExternallyOwned(order(OrderSource.SALESPERSON))).isFalse();
        assertThat(ExternalOrderGuard.isExternallyOwned(order(OrderSource.SHIFA_ADMIN))).isFalse();
        assertThat(ExternalOrderGuard.isExternallyOwned(order(OrderSource.SHOPIFY_API))).isTrue();
        assertThat(ExternalOrderGuard.isExternallyOwned(null)).isFalse();
    }

    private static OrderEntity order(OrderSource channel) {
        OrderEntity order = new OrderEntity("SHR-1001", channel, 7L,
                "Asha Kumar", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(new BigDecimal("999.00"), BigDecimal.ZERO,
                new BigDecimal("999.00"), new BigDecimal("999.00"), PaymentStatus.COD);
        return order;
    }

    @Provide
    Arbitrary<OrderSource> channels() {
        return Arbitraries.of(OrderSource.values());
    }

    @Provide
    Arbitrary<Role> roles() {
        return Arbitraries.of(Role.values());
    }
}
