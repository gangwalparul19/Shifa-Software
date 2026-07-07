package com.shifa.oms.procurement;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.inventory.StockMovement;
import com.shifa.oms.inventory.StockMovementType;
import com.shifa.oms.inventory.StockService;
import com.shifa.oms.procurement.dto.CreatePurchaseOrderRequest;
import com.shifa.oms.procurement.dto.PurchaseOrderItemRequest;
import com.shifa.oms.procurement.dto.PurchaseOrderResponse;
import com.shifa.oms.procurement.dto.ReceiveLineRequest;
import com.shifa.oms.procurement.dto.ReceivePurchaseOrderRequest;
import com.shifa.oms.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link PurchaseOrderService} (Feature C2). The
 * interface repositories are Mockito mocks; the concrete {@link StockService}
 * and {@link AuditService} are hand-written fakes (Mockito cannot mock the
 * concrete service classes in this project's setup), and a real
 * {@link CurrentUserService} is used (resolves to no principal without an auth
 * context).
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderSequenceRepository sequenceRepository;

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private ProductRepository productRepository;

    private RecordingStockService stockService;
    private PurchaseOrderService service;

    @BeforeEach
    void setUp() {
        stockService = new RecordingStockService();
        service = new PurchaseOrderService(purchaseOrderRepository, sequenceRepository,
                supplierRepository, productRepository, stockService,
                new NoopAuditService(), new CurrentUserService());
        lenient().when(purchaseOrderRepository.save(any(PurchaseOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // --- create: computes total + allocates po_number -----------------------

    @Test
    void createComputesTotalAndAllocatesPoNumber() {
        when(supplierRepository.existsById(1L)).thenReturn(true);
        when(productRepository.existsById(anyLong())).thenReturn(true);
        when(sequenceRepository.findByIdForUpdate(PurchaseOrderSequence.SINGLETON_ID))
                .thenReturn(Optional.of(new PurchaseOrderSequence(1L, 1L)));

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest(1L, "restock herbs",
                List.of(
                        new PurchaseOrderItemRequest(100L, 10, new BigDecimal("25.00")),
                        new PurchaseOrderItemRequest(101L, 4, new BigDecimal("50.50"))));

        PurchaseOrderResponse response = service.create(request);

        // 10*25.00 + 4*50.50 = 250.00 + 202.00 = 452.00
        assertThat(response.totalAmount()).isEqualByComparingTo("452.00");
        assertThat(response.poNumber()).isEqualTo("PO-0001");
        assertThat(response.status()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(response.items()).hasSize(2);
    }

    @Test
    void createWithUnknownSupplierIsNotFound() {
        when(supplierRepository.existsById(9L)).thenReturn(false);

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest(9L, null,
                List.of(new PurchaseOrderItemRequest(100L, 1, new BigDecimal("5.00"))));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Supplier 9");
    }

    @Test
    void createWithUnknownProductIsNotFound() {
        when(supplierRepository.existsById(1L)).thenReturn(true);
        when(productRepository.existsById(100L)).thenReturn(false);
        when(sequenceRepository.findByIdForUpdate(PurchaseOrderSequence.SINGLETON_ID))
                .thenReturn(Optional.of(new PurchaseOrderSequence(1L, 1L)));

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest(1L, null,
                List.of(new PurchaseOrderItemRequest(100L, 1, new BigDecimal("5.00"))));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product 100");
    }

    // --- receive: feeds inventory via restock + flips status ----------------

    @Test
    void receiveAllLinesFullyRestocksAndFlipsToReceived() {
        PurchaseOrder po = orderedPo(
                item(10L, 500L, 5, "25.00"),
                item(11L, 501L, 3, "40.00"));
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));

        ReceivePurchaseOrderRequest request = new ReceivePurchaseOrderRequest(List.of(
                new ReceiveLineRequest(10L, 5),
                new ReceiveLineRequest(11L, 3)));

        PurchaseOrderResponse response = service.receive(7L, request);

        assertThat(response.status()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(response.receivedAt()).isNotNull();
        // One restock call per received line, adding stock to the right product.
        assertThat(stockService.calls).hasSize(2);
        assertThat(stockService.calls).extracting(c -> c.productId())
                .containsExactlyInAnyOrder(500L, 501L);
        assertThat(response.items()).allSatisfy(i ->
                assertThat(i.receivedQuantity()).isEqualTo(i.quantity()));
    }

    @Test
    void receivePartialFlipsToPartiallyReceived() {
        PurchaseOrder po = orderedPo(
                item(10L, 500L, 5, "25.00"),
                item(11L, 501L, 3, "40.00"));
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));

        ReceivePurchaseOrderRequest request = new ReceivePurchaseOrderRequest(List.of(
                new ReceiveLineRequest(10L, 2)));

        PurchaseOrderResponse response = service.receive(7L, request);

        assertThat(response.status()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(response.receivedAt()).isNull();
        assertThat(stockService.calls).hasSize(1);
        assertThat(stockService.calls.get(0).productId()).isEqualTo(500L);
        assertThat(stockService.calls.get(0).quantity()).isEqualTo(2);
    }

    @Test
    void receiveMoreThanOutstandingIsRejected() {
        PurchaseOrder po = orderedPo(item(10L, 500L, 5, "25.00"));
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));

        ReceivePurchaseOrderRequest request = new ReceivePurchaseOrderRequest(List.of(
                new ReceiveLineRequest(10L, 6)));

        assertThatThrownBy(() -> service.receive(7L, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds the outstanding");
        assertThat(stockService.calls).isEmpty();
    }

    @Test
    void receiveOnCancelledPoIsRejected() {
        PurchaseOrder po = orderedPo(item(10L, 500L, 5, "25.00"));
        po.setStatus(PurchaseOrderStatus.CANCELLED);
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));

        ReceivePurchaseOrderRequest request = new ReceivePurchaseOrderRequest(List.of(
                new ReceiveLineRequest(10L, 1)));

        assertThatThrownBy(() -> service.receive(7L, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot be received");
    }

    // --- cancel: guarded by status ------------------------------------------

    @Test
    void cancelOrderedPoSucceeds() {
        PurchaseOrder po = orderedPo(item(10L, 500L, 5, "25.00"));
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));

        PurchaseOrderResponse response = service.cancel(7L);

        assertThat(response.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
    }

    @Test
    void cancelReceivedPoIsRejected() {
        PurchaseOrder po = orderedPo(item(10L, 500L, 5, "25.00"));
        po.setStatus(PurchaseOrderStatus.RECEIVED);
        when(purchaseOrderRepository.findById(7L)).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> service.cancel(7L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    // --- test fixtures -------------------------------------------------------

    private PurchaseOrder orderedPo(PurchaseOrderItem... items) {
        PurchaseOrder po = new PurchaseOrder("PO-0001", 1L, null, null);
        for (PurchaseOrderItem item : items) {
            po.addItem(item);
        }
        po.recomputeTotal();
        return po;
    }

    /** Builds a PO line item and forces its generated id via reflection (unit test only). */
    private PurchaseOrderItem item(long id, long productId, int quantity, String unitCost) {
        PurchaseOrderItem item = new PurchaseOrderItem(productId, quantity, new BigDecimal(unitCost));
        setId(item, id);
        return item;
    }

    private static void setId(Object entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records each {@code restock} call so the test can assert on them. */
    private static final class RecordingStockService extends StockService {
        record Call(Long productId, int quantity) {
        }

        final List<Call> calls = new ArrayList<>();

        RecordingStockService() {
            super(null, null, null, null);
        }

        @Override
        public StockMovement restock(Long productId, int quantity, String reason, Long userId) {
            calls.add(new Call(productId, quantity));
            return new StockMovement(productId, quantity, StockMovementType.RESTOCK, reason, quantity, userId);
        }
    }

    /** A no-op audit service so best-effort auditing never interferes with the test. */
    private static final class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null, null);
        }

        @Override
        public AuditEvent record(String action, String entityType, String entityId, String summary) {
            return null;
        }
    }
}
