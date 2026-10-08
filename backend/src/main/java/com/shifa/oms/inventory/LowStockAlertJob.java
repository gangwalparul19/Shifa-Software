package com.shifa.oms.inventory;

import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.product.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Nightly low-stock & reorder sweep (ENHANCEMENT 1.3).
 *
 * <p>The existing low-stock admin notification only fires on the <em>crossing</em>
 * event — the moment a sale drops a tracked product into the low-stock band. A
 * product that is restocked slightly, sits at/under threshold for days, or was
 * already low when the feature shipped is never re-flagged. This job closes that
 * gap: once a day it re-emits a best-effort low-stock notification for every
 * tracked product still at or below its reorder threshold (including out of
 * stock), so standing shortages get a daily nudge and nothing silently lingers.
 *
 * <p>Reuses the same {@link OutboxEventPublisher#publishLowStock} path as the
 * on-sale alert, so the notification shape, routing, and de-duplication are
 * identical. Gated by {@code app.inventory.low-stock-sweep-enabled} (default
 * true) + {@code LOW_STOCK_SWEEP_CRON} (default 07:30, before the 8 AM owner
 * report). Failures are logged, never rethrown.
 */
@Component
public class LowStockAlertJob {

    private static final Logger log = LoggerFactory.getLogger(LowStockAlertJob.class);

    private final StockService stockService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final boolean enabled;

    public LowStockAlertJob(StockService stockService,
                            OutboxEventPublisher outboxEventPublisher,
                            @Value("${app.inventory.low-stock-sweep-enabled:true}") boolean enabled) {
        this.stockService = stockService;
        this.outboxEventPublisher = outboxEventPublisher;
        this.enabled = enabled;
    }

    /** Scheduled entry point: re-flags every standing low/out-of-stock tracked product. */
    @Scheduled(cron = "${LOW_STOCK_SWEEP_CRON:0 30 7 * * *}")
    @Transactional(readOnly = true)
    public void sweep() {
        if (!enabled) {
            log.debug("Low-stock sweep skipped: app.inventory.low-stock-sweep-enabled=false.");
            return;
        }
        try {
            List<Product> low = stockService.lowStock(true); // include out-of-stock
            int emitted = 0;
            for (Product p : low) {
                try {
                    outboxEventPublisher.publishLowStock(
                            p.getId(), p.getSku(), p.getName(),
                            p.getStockQuantity(), stockService.thresholdFor(p),
                            p.getStockQuantity() <= 0);
                    emitted++;
                } catch (RuntimeException e) {
                    log.warn("Low-stock sweep: failed to enqueue alert for product {}: {}",
                            p.getId(), e.getMessage());
                }
            }
            if (emitted > 0) {
                log.info("Low-stock sweep: re-flagged {} product(s) at/below reorder threshold.", emitted);
            }
        } catch (RuntimeException e) {
            log.warn("Low-stock sweep cycle failed: {}", e.getMessage());
        }
    }
}
