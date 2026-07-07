package com.shifa.oms.product;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit + property tests for the pure {@link StockStatus} helper (Catalog &
 * Discovery): in/low/out classification and the track-vs-untracked rule.
 */
class StockStatusTest {

    // --- Example-based edge cases ------------------------------------------

    @Test
    void untrackedProductIsAlwaysInStockRegardlessOfQuantity() {
        assertThat(StockStatus.of(false, -100)).isEqualTo(StockStatus.IN_STOCK);
        assertThat(StockStatus.of(false, 0)).isEqualTo(StockStatus.IN_STOCK);
        assertThat(StockStatus.of(false, 5)).isEqualTo(StockStatus.IN_STOCK);
        assertThat(StockStatus.of(false, 9999)).isEqualTo(StockStatus.IN_STOCK);
    }

    @Test
    void trackedZeroOrNegativeIsOutOfStock() {
        assertThat(StockStatus.of(true, 0)).isEqualTo(StockStatus.OUT_OF_STOCK);
        assertThat(StockStatus.of(true, -1)).isEqualTo(StockStatus.OUT_OF_STOCK);
    }

    @Test
    void trackedSmallPositiveQuantityIsLowStock() {
        assertThat(StockStatus.of(true, 1)).isEqualTo(StockStatus.LOW_STOCK);
        assertThat(StockStatus.of(true, StockStatus.LOW_STOCK_THRESHOLD))
                .isEqualTo(StockStatus.LOW_STOCK);
    }

    @Test
    void trackedAboveThresholdIsInStock() {
        assertThat(StockStatus.of(true, StockStatus.LOW_STOCK_THRESHOLD + 1))
                .isEqualTo(StockStatus.IN_STOCK);
        assertThat(StockStatus.of(true, 1000)).isEqualTo(StockStatus.IN_STOCK);
    }

    @Test
    void onlyOutOfStockIsNotPurchasable() {
        assertThat(StockStatus.IN_STOCK.isPurchasable()).isTrue();
        assertThat(StockStatus.LOW_STOCK.isPurchasable()).isTrue();
        assertThat(StockStatus.OUT_OF_STOCK.isPurchasable()).isFalse();
    }

    // --- Properties --------------------------------------------------------

    @Property(tries = 300)
    void untrackedIsAlwaysInStock(@ForAll @IntRange(min = -1000, max = 1000) int qty) {
        assertThat(StockStatus.of(false, qty)).isEqualTo(StockStatus.IN_STOCK);
    }

    @Property(tries = 500)
    void trackedClassificationMatchesQuantityBands(
            @ForAll @IntRange(min = -50, max = 500) int qty) {
        StockStatus status = StockStatus.of(true, qty);
        if (qty <= 0) {
            assertThat(status).isEqualTo(StockStatus.OUT_OF_STOCK);
        } else if (qty <= StockStatus.LOW_STOCK_THRESHOLD) {
            assertThat(status).isEqualTo(StockStatus.LOW_STOCK);
        } else {
            assertThat(status).isEqualTo(StockStatus.IN_STOCK);
        }
    }
}
