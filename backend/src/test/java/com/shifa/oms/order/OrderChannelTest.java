package com.shifa.oms.order;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit tests for {@link OrderSource} — the order CHANNEL that
 * distinguishes a Shopify-originated order from one punched in the Shifa Admin
 * Portal (spec {@code shopify-quikshipx-order-sync}, Req 1).
 *
 * <p>The column still holds two legacy values ({@code STOREFRONT} from the removed
 * public checkout, {@code SALESPERSON} from before the Shopify pivot), so the
 * risk this pins down is a channel filter or a report silently dropping historic
 * rows. {@link OrderSource#canonical()} folds both onto {@code SHIFA_ADMIN} and
 * {@link OrderSource#storedEquivalents()} is what a query must match against.
 */
class OrderChannelTest {

    // --- Canonicalisation ---------------------------------------------------

    @Test
    void everyStoredValueCanonicalisesToExactlyOneOfTheTwoChannels() {
        for (OrderSource source : OrderSource.values()) {
            assertThat(source.canonical())
                    .as("channel of %s", source)
                    .isIn(OrderSource.SHOPIFY_API, OrderSource.SHIFA_ADMIN);
        }
    }

    @Test
    void legacyValuesFoldOntoShifaAdmin() {
        // Both predate the Shopify pivot and were Shifa-originated, so a
        // SHIFA_ADMIN filter must return them (Req 1.6).
        assertThat(OrderSource.SALESPERSON.canonical()).isEqualTo(OrderSource.SHIFA_ADMIN);
        assertThat(OrderSource.STOREFRONT.canonical()).isEqualTo(OrderSource.SHIFA_ADMIN);
    }

    @Test
    void shopifyIsItsOwnChannel() {
        assertThat(OrderSource.SHOPIFY_API.canonical()).isEqualTo(OrderSource.SHOPIFY_API);
        assertThat(OrderSource.SHIFA_ADMIN.canonical()).isEqualTo(OrderSource.SHIFA_ADMIN);
    }

    @Test
    void canonicalisationIsIdempotent() {
        // Folding a folded value must not move it again, otherwise grouping a
        // grouped result would drift.
        for (OrderSource source : OrderSource.values()) {
            assertThat(source.canonical().canonical()).isEqualTo(source.canonical());
        }
    }

    @Test
    void isShopifyAgreesWithCanonical() {
        for (OrderSource source : OrderSource.values()) {
            assertThat(source.isShopify())
                    .as("isShopify of %s", source)
                    .isEqualTo(source.canonical() == OrderSource.SHOPIFY_API);
        }
    }

    // --- Partition guarantees ----------------------------------------------

    @Test
    void storedEquivalentsPartitionEveryValueExactlyOnce() {
        List<OrderSource> all = new ArrayList<>();
        for (OrderSource channel : List.of(OrderSource.SHOPIFY_API, OrderSource.SHIFA_ADMIN)) {
            all.addAll(channel.storedEquivalents());
        }

        // No stored value is claimed by both channels, so no order is double-counted
        // in a per-channel report (the channel-partition invariant, Req 12.4).
        assertThat(all).doesNotHaveDuplicates();
        // Every stored value is claimed by some channel, so none is unreachable.
        assertThat(EnumSet.copyOf(all)).isEqualTo(EnumSet.allOf(OrderSource.class));
    }

    @Test
    void storedEquivalentsAgreesWithCanonical() {
        for (OrderSource source : OrderSource.values()) {
            assertThat(source.storedEquivalents())
                    .as("equivalents of %s", source)
                    .allMatch(v -> v.canonical() == source.canonical())
                    .contains(source);
        }
    }

    @Test
    void shifaAdminMatchesBothLegacyValues() {
        // This is the concrete query behaviour the Orders page depends on.
        assertThat(OrderSource.SHIFA_ADMIN.storedEquivalents())
                .containsExactlyInAnyOrder(
                        OrderSource.STOREFRONT, OrderSource.SALESPERSON, OrderSource.SHIFA_ADMIN);
        assertThat(OrderSource.SHOPIFY_API.storedEquivalents())
                .containsExactly(OrderSource.SHOPIFY_API);
    }
}
