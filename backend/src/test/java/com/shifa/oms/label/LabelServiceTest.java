package com.shifa.oms.label;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.statemachine.IllegalStatusTransitionException;
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
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link LabelService} and its collaborators
 * (Req 10.1-10.4):
 * <ul>
 *   <li>COD amount is included on the label content for COD / Partially_Paid and
 *       omitted for Fully_Paid;</li>
 *   <li>generating the label on approval flips the order to Label_Generated and
 *       records a status-history row;</li>
 *   <li>single and bulk PDFs are produced (non-empty, well-formed PDF bytes),
 *       with one block per requested order for bulk;</li>
 *   <li>an empty bulk request and unknown order ids are rejected.</li>
 * </ul>
 * A real in-memory storage backend is used so no disk or database is touched.
 */
@ExtendWith(MockitoExtension.class)
class LabelServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private LabelService labelService;
    private final LabelContentBuilder builder = new LabelContentBuilder();

    @BeforeEach
    void setUp() {
        labelService = new LabelService(orderRepository, new InMemoryStorage());
    }

    // --- COD-iff rule on the content model (Req 10.2) -----------------------

    @Test
    void codIncludedForCodOrder() {
        InternalLabelContent content = builder.buildInternal(
                order(OrderStatus.APPROVED, PaymentStatus.COD, new BigDecimal("240.00")));
        assertThat(content.codApplicable()).isTrue();
        assertThat(content.codAmount()).isEqualByComparingTo("240.00");
    }

    @Test
    void codIncludedForPartiallyPaidOrder() {
        InternalLabelContent content = builder.buildInternal(
                order(OrderStatus.APPROVED, PaymentStatus.PARTIALLY_PAID, new BigDecimal("100.00")));
        assertThat(content.codApplicable()).isTrue();
        assertThat(content.codAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void codOmittedForFullyPaidOrder() {
        InternalLabelContent content = builder.buildInternal(
                order(OrderStatus.APPROVED, PaymentStatus.FULLY_PAID, BigDecimal.ZERO));
        assertThat(content.codApplicable()).isFalse();
        assertThat(content.codAmount()).isNull();
    }

    // --- Label generation flips status to Label_Generated (Req 10.3) --------

    @Test
    void generateOnApprovalTransitionsToLabelGeneratedAndStoresPdf() {
        OrderEntity order = order(OrderStatus.APPROVED, PaymentStatus.COD, new BigDecimal("240.00"));

        String key = labelService.generateInternalLabelOnApproval(order, "admin");

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.LABEL_GENERATED);
        assertThat(order.getStatusHistory()).hasSize(1);
        assertThat(order.getStatusHistory().get(0).getFromStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(order.getStatusHistory().get(0).getToStatus()).isEqualTo(OrderStatus.LABEL_GENERATED);
        assertThat(order.getStatusHistory().get(0).getSource()).isEqualTo("SYSTEM");
        assertThat(key).startsWith("labels/internal/");
    }

    @Test
    void generateOnApprovalFromNonApprovedOrderIsRejected() {
        OrderEntity order = order(OrderStatus.PACKED, PaymentStatus.COD, new BigDecimal("50.00"));

        assertThatThrownBy(() -> labelService.generateInternalLabelOnApproval(order, "admin"))
                .isInstanceOf(IllegalStatusTransitionException.class);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PACKED);
        assertThat(order.getStatusHistory()).isEmpty();
    }

    // --- Single & bulk PDF output (Req 10.4) --------------------------------

    @Test
    void singleLabelPdfIsAWellFormedPdf() {
        OrderEntity order = order(OrderStatus.LABEL_GENERATED, PaymentStatus.COD, new BigDecimal("240.00"));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        byte[] pdf = labelService.internalLabelPdf(1L);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
    }

    @Test
    void bulkLabelPdfProducedForMultipleOrders() {
        OrderEntity a = order(OrderStatus.LABEL_GENERATED, PaymentStatus.COD, new BigDecimal("240.00"));
        OrderEntity b = order(OrderStatus.LABEL_GENERATED, PaymentStatus.FULLY_PAID, BigDecimal.ZERO);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(a));
        when(orderRepository.findById(2L)).thenReturn(Optional.of(b));

        byte[] pdf = labelService.bulkInternalLabelPdf(List.of(1L, 2L));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");
    }

    @Test
    void bulkBuilderReturnsOneBlockPerRequestedOrder() {
        OrderEntity a = order(OrderStatus.LABEL_GENERATED, PaymentStatus.COD, new BigDecimal("240.00"));
        OrderEntity b = order(OrderStatus.LABEL_GENERATED, PaymentStatus.FULLY_PAID, BigDecimal.ZERO);
        OrderEntity c = order(OrderStatus.LABEL_GENERATED, PaymentStatus.PARTIALLY_PAID, new BigDecimal("30.00"));

        List<InternalLabelContent> blocks = builder.buildBulk(List.of(a, b, c));

        assertThat(blocks).hasSize(3);
    }

    @Test
    void emptyBulkRequestIsRejected() {
        assertThatThrownBy(() -> labelService.bulkInternalLabelPdf(List.of()))
                .isInstanceOf(ValidationException.class);
    }

    // --- Helpers ------------------------------------------------------------

    private OrderEntity order(OrderStatus status, PaymentStatus paymentStatus, BigDecimal cod) {
        OrderEntity order = new OrderEntity(
                "SHR-000123", OrderSource.SALESPERSON, 7L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        BigDecimal total = new BigDecimal("240.00");
        BigDecimal received = total.subtract(cod);
        order.addLineItem(new OrderLineItem(1L, "Neem Capsules", 2,
                new BigDecimal("120.00"), new BigDecimal("240.00")));
        order.applyAmounts(total, received, total.subtract(received), cod, paymentStatus);
        order.setOrderStatus(status);
        return order;
    }

    /** In-memory {@link StorageService} keeping stored bytes in a list of keys. */
    private static final class InMemoryStorage implements StorageService {
        private final List<String> keys = new ArrayList<>();

        @Override
        public StoredObjectRef store(String prefix, String originalFilename,
                                     String contentType, byte[] content) {
            String key = prefix + "/" + originalFilename;
            keys.add(key);
            return new StoredObjectRef(key);
        }

        @Override
        public Optional<StoredObject> load(String key) {
            return Optional.empty();
        }
    }
}
