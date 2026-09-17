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
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.returns.dto.ReturnResponse;
import com.shifa.oms.statemachine.OrderStatus;
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
        OrderEntity order = orderIn(OrderStatus.DELIVERED, 1);
        ReflectionTestUtils.setField(order, "id", 1L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(returnRepository.existsByOrderIdAndStatusIn(anyLong(), any())).thenReturn(false);

        ReturnResponse response = service.create("1", "Damaged", "box crushed");

        assertThat(response.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(response.reason()).isEqualTo("Damaged");
        assertThat(response.restocked()).isFalse();
    }

    @Test
    void createAcceptsOrderCodeInsteadOfNumericId() {
        OrderEntity order = orderIn(OrderStatus.DELIVERED, 1);
        ReflectionTestUtils.setField(order, "id", 1L);
        // The order code is not purely numeric, so resolveOrder goes straight to
        // the code lookup (findByOrderCode), never touching findById.
        when(orderRepository.findByOrderCode("SHR-000123")).thenReturn(Optional.of(order));
        when(returnRepository.existsByOrderIdAndStatusIn(anyLong(), any())).thenReturn(false);

        ReturnResponse response = service.create("SHR-000123", "Damaged", null);

        assertThat(response.status()).isEqualTo(ReturnStatus.REQUESTED);
    }

    @Test
    void createFromNonReturnableStatusIsRejected() {
        OrderEntity order = orderIn(OrderStatus.APPROVED, 1);
        ReflectionTestUtils.setField(order, "id", 1L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.create("1", "Damaged", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("delivered or RTO");
    }

    @Test
    void createForMissingOrderIsNotFound() {
        when(orderRepository.findById(9L)).thenReturn(Optional.empty());
        when(orderRepository.findByOrderCode("9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("9", "Damaged", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- create: one active return per order --------------------------------

    @Test
    void createBlockedWhenAnActiveReturnAlreadyExists() {
        OrderEntity order = orderIn(OrderStatus.RTO, 1);
        ReflectionTestUtils.setField(order, "id", 1L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(returnRepository.existsByOrderIdAndStatusIn(anyLong(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.create("1", "Wrong size", null))
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

    // --- RTO auto-return: credit-note value vs cash refund (V64) ------------

    /**
     * The CA scenario: a ₹1000 order partially paid (₹300 prepaid, ₹700 COD) that
     * later RTOs. Under GST the whole supply is reversed, so the credit note must
     * carry the full ₹1000; but the only cash owed back is the ₹300 actually
     * collected. Conflating the two reported a ₹1000 cash refund that never
     * happened.
     */
    @Test
    void rtoAutoReturnCreditsTheFullSupplyButRefundsOnlyTheCashCollected() {
        OrderEntity order = orderIn(OrderStatus.RTO, 2);
        ReflectionTestUtils.setField(order, "id", 1L);
        order.applyAmounts(new BigDecimal("1000.00"), new BigDecimal("300.00"),
                new BigDecimal("700.00"), new BigDecimal("700.00"), PaymentStatus.PARTIALLY_PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(returnRepository.existsByOrderIdAndStatusIn(anyLong(), any())).thenReturn(false);

        ReturnResponse response = service.createAutoReturnForRto(1L, "CUSTOMER_UNAVAILABLE");

        assertThat(response.status()).isEqualTo(ReturnStatus.REFUNDED);
        // GST: the entire invoice value is reversed by the credit note.
        assertThat(response.creditNoteValue()).isEqualByComparingTo("1000.00");
        // Money: only the prepaid part is actually refundable.
        assertThat(response.refundAmount()).isEqualByComparingTo("300.00");
    }

    /** A pure COD RTO: full credit note, but no cash was ever taken, so no refund. */
    @Test
    void rtoAutoReturnOnACodOrderRefundsNoCash() {
        OrderEntity order = orderIn(OrderStatus.RTO, 1);
        ReflectionTestUtils.setField(order, "id", 1L);
        order.applyAmounts(new BigDecimal("1000.00"), BigDecimal.ZERO,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), PaymentStatus.COD);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(returnRepository.existsByOrderIdAndStatusIn(anyLong(), any())).thenReturn(false);

        ReturnResponse response = service.createAutoReturnForRto(1L, "CUSTOMER_REFUSED");

        assertThat(response.creditNoteValue()).isEqualByComparingTo("1000.00");
        assertThat(response.refundAmount()).isEqualByComparingTo("0.00");
    }

    /** Idempotent: a second RTO mark must not raise a duplicate return. */
    @Test
    void rtoAutoReturnIsSkippedWhenAnActiveReturnAlreadyExists() {
        when(returnRepository.existsByOrderIdAndStatusIn(anyLong(), any())).thenReturn(true);

        assertThat(service.createAutoReturnForRto(1L, "OTHER")).isNull();
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
