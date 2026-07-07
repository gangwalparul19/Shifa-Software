package com.shifa.oms.returns;

import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.inventory.StockMovement;
import com.shifa.oms.inventory.StockMovementType;
import com.shifa.oms.inventory.StockService;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.returns.dto.ReturnResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * Example-based unit tests for {@link ReturnService} ("operations depth"
 * Feature 1). The interface repositories are Mockito mocks; the concrete
 * {@link StockService} and {@link AuditService} are hand-written fakes (Mockito
 * cannot mock the concrete service classes in this project's setup), and a real
 * {@link CurrentUserService} is used (resolves to no principal without an auth
 * context).
 */
@ExtendWith(MockitoExtension.class)
class ReturnServiceTest {

    @Mock
    private OrderReturnRepository returnRepository;

    @Mock
    private OrderRepository orderRepository;

    private RecordingStockService stockService;
    private ReturnService service;

    @BeforeEach
    void setUp() {
        stockService = new RecordingStockService();
        AuditService auditService = new NoopAuditService();
        service = new ReturnService(returnRepository, orderRepository, stockService,
                auditService, new CurrentUserService());
        lenient().when(returnRepository.save(any(OrderReturn.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private OrderEntity orderIn(OrderStatus status, int lineItemCount) {
        OrderEntity order = new OrderEntity(
                "SHR-000123", OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.setOrderStatus(status);
        for (int i = 0; i < lineItemCount; i++) {
            order.addLineItem(new OrderLineItem((long) (100 + i), "Product " + i, 2,
                    new BigDecimal("50.00"), new BigDecimal("100.00")));
        }
        return order;
    }

    // --- create: only from a returnable status -----------------------------

    @Test
    void createSucceedsFromDeliveredOrder() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderIn(OrderStatus.DELIVERED, 1)));
        when(returnRepository.existsByOrderIdAndStatusIn(anyLong(), any())).thenReturn(false);

        ReturnResponse response = service.create(1L, "Damaged", "box crushed");

        assertThat(response.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(response.reason()).isEqualTo("Damaged");
        assertThat(response.restocked()).isFalse();
    }

    @Test
    void createFromNonReturnableStatusIsRejected() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderIn(OrderStatus.APPROVED, 1)));

        assertThatThrownBy(() -> service.create(1L, "Damaged", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("delivered or RTO");
    }

    @Test
    void createForMissingOrderIsNotFound() {
        when(orderRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(9L, "Damaged", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- create: one active return per order --------------------------------

    @Test
    void createBlockedWhenAnActiveReturnAlreadyExists() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderIn(OrderStatus.RTO, 1)));
        when(returnRepository.existsByOrderIdAndStatusIn(anyLong(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.create(1L, "Wrong size", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already has an active return");
    }

    // --- approve: restocks each line item and flips the flag ----------------

    @Test
    void approveWithRestockReturnsEachLineItemToStockAndFlipsFlag() {
        OrderReturn ret = new OrderReturn(1L, "Damaged", null, null);
        when(returnRepository.findById(5L)).thenReturn(Optional.of(ret));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderIn(OrderStatus.DELIVERED, 2)));

        ReturnResponse response = service.approve(5L, true, new BigDecimal("240.00"));

        assertThat(response.status()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(response.restocked()).isTrue();
        assertThat(response.refundAmount()).isEqualByComparingTo("240.00");
        // One returnToStock call per line item.
        assertThat(stockService.calls).hasSize(2);
        assertThat(stockService.calls).extracting(c -> c.productId)
                .containsExactlyInAnyOrder(100L, 101L);
        assertThat(stockService.calls).allSatisfy(c -> assertThat(c.quantity).isEqualTo(2));
    }

    @Test
    void approveWithoutRestockDoesNotTouchStock() {
        OrderReturn ret = new OrderReturn(1L, "Changed mind", null, null);
        when(returnRepository.findById(5L)).thenReturn(Optional.of(ret));

        ReturnResponse response = service.approve(5L, false, null);

        assertThat(response.status()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(response.restocked()).isFalse();
        assertThat(stockService.calls).isEmpty();
    }

    // --- illegal transitions -------------------------------------------------

    @Test
    void approveAlreadyRefundedReturnIsRejected() {
        OrderReturn ret = new OrderReturn(1L, "Damaged", null, null);
        ret.changeStatus(ReturnStatus.APPROVED);
        ret.changeStatus(ReturnStatus.REFUNDED);
        when(returnRepository.findById(5L)).thenReturn(Optional.of(ret));

        assertThatThrownBy(() -> service.approve(5L, false, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Cannot approve");
        assertThat(stockService.calls).isEmpty();
    }

    @Test
    void markRefundedFromRequestedIsRejected() {
        OrderReturn ret = new OrderReturn(1L, "Damaged", null, null); // REQUESTED
        when(returnRepository.findById(5L)).thenReturn(Optional.of(ret));

        assertThatThrownBy(() -> service.markRefunded(5L, new BigDecimal("10.00")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mark refunded");
    }

    // --- markRefunded sets amount + status ----------------------------------

    @Test
    void markRefundedSetsAmountAndStatus() {
        OrderReturn ret = new OrderReturn(1L, "Damaged", null, null);
        ret.changeStatus(ReturnStatus.APPROVED);
        when(returnRepository.findById(5L)).thenReturn(Optional.of(ret));

        ReturnResponse response = service.markRefunded(5L, new BigDecimal("199.50"));

        assertThat(response.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(response.refundAmount()).isEqualByComparingTo("199.50");
        assertThat(response.updatedAt()).isNotNull();
    }

    // --- reject transitions to REJECTED and stores notes --------------------

    @Test
    void rejectTransitionsToRejectedAndStoresNotes() {
        OrderReturn ret = new OrderReturn(1L, "Damaged", null, null);
        when(returnRepository.findById(5L)).thenReturn(Optional.of(ret));

        ReturnResponse response = service.reject(5L, "Outside return window");

        assertThat(response.status()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(response.notes()).isEqualTo("Outside return window");
    }

    // --- list filters / paginate --------------------------------------------

    @Test
    void listReturnsPagedEnvelope() {
        OrderReturn ret = new OrderReturn(1L, "Damaged", null, null);
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(0, 20);
        when(returnRepository.search(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(ret), pageable, 1));

        PageResponse<ReturnResponse> page =
                service.list(ReturnStatus.REQUESTED, "dam", null, null, pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement()
                .satisfies(r -> assertThat(r.reason()).isEqualTo("Damaged"));
    }

    // --- Hand-written fakes for the concrete service dependencies -----------

    /** Records each {@code returnToStock} call so the test can assert on them. */
    private static final class RecordingStockService extends StockService {
        record Call(Long productId, int quantity) {
        }

        final List<Call> calls = new ArrayList<>();

        RecordingStockService() {
            super(null, null, null, null);
        }

        @Override
        public StockMovement returnToStock(Long productId, int quantity, String reason, Long userId) {
            calls.add(new Call(productId, quantity));
            return new StockMovement(productId, quantity, StockMovementType.RETURN, reason, quantity, userId);
        }
    }

    /** A no-op audit service so best-effort auditing never interferes with the test. */
    private static final class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null, null);
        }

        @Override
        public com.shifa.oms.audit.AuditEvent record(String action, String entityType,
                                                     String entityId, String summary) {
            return null;
        }
    }
}
