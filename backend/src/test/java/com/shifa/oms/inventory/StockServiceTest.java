package com.shifa.oms.inventory;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.AppSettingsRepository;
import com.shifa.oms.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StockService} (Feature 1). Repositories are mocked
 * interfaces; the concrete {@link OutboxEventPublisher} and {@link SettingsService}
 * are real instances over their mocked interface repositories (concrete classes
 * are not mockable on this JVM), so the notification + threshold paths are
 * genuinely exercised without a database.
 *
 * <p>Covers: sale decrement + SALE movement; untracked products are ignored;
 * insufficient stock is rejected; restock increases stock + records RESTOCK;
 * the negative-guard on adjust; the low-stock boundary; and the low-stock
 * notification crossing the threshold band.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private AppSettingsRepository appSettingsRepository;

    private StockService service;
    private List<StockMovement> savedMovements;
    private List<OutboxEvent> savedEvents;

    @BeforeEach
    void setUp() {
        savedMovements = new ArrayList<>();
        savedEvents = new ArrayList<>();
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> {
            StockMovement m = inv.getArgument(0);
            savedMovements.add(m);
            return m;
        });
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> {
            OutboxEvent e = inv.getArgument(0);
            savedEvents.add(e);
            return e;
        });
        AppSettings settings = new AppSettings();
        settings.setLowStockThreshold(5);
        lenient().when(appSettingsRepository.findById(AppSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings));

        service = new StockService(
                productRepository,
                stockMovementRepository,
                new OutboxEventPublisher(outboxEventRepository),
                new SettingsService(appSettingsRepository));
    }

    // --- Auto-decrement on sale ---------------------------------------------

    @Test
    void recordSaleDecrementsTrackedStockAndRecordsSaleMovement() {
        Product product = tracked(10);

        service.recordSale(product, 3, "Order SHR-1", 7L);

        assertThat(product.getStockQuantity()).isEqualTo(7);
        assertThat(savedMovements).hasSize(1);
        StockMovement movement = savedMovements.get(0);
        assertThat(movement.getDelta()).isEqualTo(-3);
        assertThat(movement.getMovementType()).isEqualTo(StockMovementType.SALE);
        assertThat(movement.getBalanceAfter()).isEqualTo(7);
        assertThat(movement.getReason()).isEqualTo("Order SHR-1");
    }

    @Test
    void recordSaleIgnoresUntrackedProduct() {
        Product product = tracked(10);
        product.setTrackInventory(false);

        service.recordSale(product, 4, "Order SHR-2", null);

        assertThat(product.getStockQuantity()).isEqualTo(10);
        assertThat(savedMovements).isEmpty();
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void recordSaleRejectsInsufficientStock() {
        Product product = tracked(2);

        assertThatThrownBy(() -> service.recordSale(product, 5, "Order SHR-3", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Insufficient stock");

        assertThat(product.getStockQuantity()).isEqualTo(2);
        assertThat(savedMovements).isEmpty();
    }

    // --- Restock / adjust ----------------------------------------------------

    @Test
    void restockIncreasesStockAndRecordsRestockMovement() {
        Product product = tracked(4);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        StockMovement movement = service.restock(1L, 10, "new batch", 9L);

        assertThat(product.getStockQuantity()).isEqualTo(14);
        assertThat(movement.getDelta()).isEqualTo(10);
        assertThat(movement.getMovementType()).isEqualTo(StockMovementType.RESTOCK);
        assertThat(movement.getBalanceAfter()).isEqualTo(14);
        assertThat(movement.getCreatedBy()).isEqualTo(9L);
    }

    @Test
    void restockRejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> service.restock(1L, 0, "noop", null))
                .isInstanceOf(ValidationException.class);
        assertThat(savedMovements).isEmpty();
    }

    @Test
    void adjustRejectsDrivingStockNegative() {
        Product product = tracked(3);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.adjust(1L, -5, StockMovementType.ADJUSTMENT, "damage", 1L))
                .isInstanceOf(ValidationException.class);

        assertThat(product.getStockQuantity()).isEqualTo(3);
        assertThat(savedMovements).isEmpty();
    }

    // --- Low-stock detection boundary ---------------------------------------

    @Test
    void isLowStockHonoursThresholdBoundary() {
        // Default threshold is 5: qty 5 is low (0 < 5 <= 5); qty 6 is not; qty 0 is out (not low).
        assertThat(service.isLowStock(tracked(5))).isTrue();
        assertThat(service.isLowStock(tracked(6))).isFalse();
        assertThat(service.isLowStock(tracked(1))).isTrue();
        assertThat(service.isLowStock(tracked(0))).isFalse();

        Product untracked = tracked(1);
        untracked.setTrackInventory(false);
        assertThat(service.isLowStock(untracked)).isFalse();
    }

    // --- Low-stock notification crossing ------------------------------------

    @Test
    void saleCrossingIntoLowStockBandEmitsNotification() {
        Product product = tracked(6); // above threshold 5

        service.recordSale(product, 2, "Order SHR-9", null); // 6 -> 4 crosses into band

        List<OutboxEvent> lowStock = savedEvents.stream()
                .filter(e -> OutboxEvent.EVENT_LOW_STOCK.equals(e.getEventType()))
                .toList();
        assertThat(lowStock).hasSize(1);
        assertThat(lowStock.get(0).getPayload().get("stockQuantity")).isEqualTo(4);
        assertThat(lowStock.get(0).getPayload().get("outOfStock")).isEqualTo(false);
    }

    @Test
    void saleAlreadyWithinLowStockBandDoesNotReEmit() {
        Product product = tracked(4); // already at/below threshold 5

        service.recordSale(product, 1, "Order SHR-10", null); // 4 -> 3, no crossing

        assertThat(savedEvents.stream()
                .filter(e -> OutboxEvent.EVENT_LOW_STOCK.equals(e.getEventType()))
                .toList()).isEmpty();
    }

    // --- Helper -------------------------------------------------------------

    private Product tracked(int stock) {
        Product product = new Product("SKU-1", "Neem Capsules", "d",
                new BigDecimal("199.00"), new BigDecimal("149.00"), ProductVisibility.PUBLISHED);
        product.setTrackInventory(true);
        product.setStockQuantity(stock);
        ReflectionTestUtils.setField(product, "id", 1L);
        return product;
    }
}
