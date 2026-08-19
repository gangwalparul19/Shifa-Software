package com.shifa.oms.order;

import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.courier.TrackingService;
import com.shifa.oms.inventory.StockMovementRepository;
import com.shifa.oms.inventory.StockService;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.settings.AppSettingsRepository;
import com.shifa.oms.settings.SettingsService;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.Mockito.mock;

/**
 * Shared wiring for the salesperson order-creation property tests (Properties 2,
 * 9, 11). Builds a real {@link OrderService} over Mockito-mocked <em>interfaces</em>
 * (repositories/storage) and real concrete collaborators — honouring the Java 25
 * runtime gotcha that concrete classes must not be Mockito-mocked. The caller
 * stubs the two repositories it cares about; the remaining collaborators are inert
 * (products default to {@code trackInventory = false}, so stock reservation and
 * the outbox/settings paths are no-ops during creation).
 */
final class OrderCreationTestSupport {

    private OrderCreationTestSupport() {
    }

    /** A published product with a positive sale price and inventory tracking off. */
    static Product publishedProduct(long id, String salePrice) {
        BigDecimal sp = new BigDecimal(salePrice);
        // MRP is the price-band ceiling; keep it >= sale price so the default rate is in band.
        return new Product("SKU-" + id, "Product " + id, "desc",
                sp.max(new BigDecimal("999.00")), sp, ProductVisibility.PUBLISHED);
    }

    /** A real {@link OrderService} wired over the caller's stubbed repositories. */
    static OrderService service(OrderRepository orderRepository,
                                com.shifa.oms.product.ProductRepository productRepository) {
        StorageService storageService = new StorageService() {
            @Override
            public StoredObjectRef store(String prefix, String originalFilename,
                                         String contentType, byte[] content) {
                return new StoredObjectRef(prefix + "/" + originalFilename);
            }

            @Override
            public Optional<StoredObject> load(String key) {
                return Optional.empty();
            }
        };
        SettingsService settingsService = new SettingsService(mock(AppSettingsRepository.class));
        StockService stockService = new StockService(
                productRepository,
                mock(StockMovementRepository.class),
                new OutboxEventPublisher(mock(OutboxEventRepository.class)),
                settingsService);
        TrackingService trackingService = new TrackingService(
                orderRepository,
                mock(CourierRecordRepository.class),
                mock(CourierCompanyRepository.class));
        return new OrderService(
                orderRepository,
                productRepository,
                new OrderCodeGenerator(),
                storageService,
                new SalespersonScopeResolver(),
                trackingService,
                stockService,
                mock(com.shifa.oms.product.ProductImageRepository.class));
    }
}
