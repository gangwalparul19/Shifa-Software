package com.shifa.oms.courier;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration-style test (mock Courier API) for AWB request/response and label
 * retrieval (Req 12.1, 12.2). Wires the real {@link CourierAssignmentService} and
 * {@link ShippingLabelService} against mocked repositories and an in-memory
 * storage backend, with a stub {@link CourierClient} standing in for the courier
 * API — so no database is required.
 */
class CourierAssignmentIntegrationTest {

    private OrderRepository orderRepository;
    private CourierRecordRepository courierRecordRepository;
    private CourierCompanyRepository courierCompanyRepository;
    private InMemoryStorage storage;
    private CourierAssignmentService assignmentService;
    private CourierRecord savedRecord;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        courierRecordRepository = mock(CourierRecordRepository.class);
        courierCompanyRepository = mock(CourierCompanyRepository.class);
        storage = new InMemoryStorage();

        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(courierRecordRepository.findByOrderId(any())).thenReturn(Optional.empty());
        when(courierRecordRepository.save(any(CourierRecord.class))).thenAnswer(i -> {
            savedRecord = i.getArgument(0);
            return savedRecord;
        });
        when(courierCompanyRepository.findFirstByName(any()))
                .thenReturn(Optional.of(new CourierCompany("Shifa Express", "https://track.example.com/{awb}")));

        ShippingLabelService shippingLabelService = new ShippingLabelService(
                orderRepository, courierRecordRepository, courierCompanyRepository);

        CourierClient client = new CourierClient() {
            @Override
            public CourierAssignmentResult assign(CourierAssignmentRequest request) {
                // Echo back a deterministic AWB + ETA for the requested order.
                return new CourierAssignmentResult("AWB-" + request.orderCode(),
                        "Shifa Express", LocalDate.now().plusDays(4));
            }

            @Override
            public Optional<CourierTrackingEvent> pollLatest(String awb) {
                return Optional.empty();
            }
        };

        CourierProperties properties = new CourierProperties(
                "MOCK", null, null, null, Duration.ofSeconds(10), 3,
                Duration.ofSeconds(30), "Shifa Express");

        assignmentService = new CourierAssignmentService(
                orderRepository, courierRecordRepository, courierCompanyRepository,
                client, shippingLabelService, storage, properties);
    }

    @Test
    void assignmentRequestsAwbStoresLabelAndMovesToCourierAssigned() {
        OrderEntity order = packedCodOrder();
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        assignmentService.assignForOrder(10L);

        // AWB stored and status advanced (Req 12.2).
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COURIER_ASSIGNED);
        assertThat(order.getStatusHistory()).hasSize(1);
        assertThat(order.getStatusHistory().get(0).getToStatus()).isEqualTo(OrderStatus.COURIER_ASSIGNED);

        assertThat(savedRecord).isNotNull();
        assertThat(savedRecord.getAwb()).isEqualTo("AWB-SHR-000777");
        assertThat(savedRecord.getEstimatedDelivery()).isNotNull();
        assertThat(savedRecord.getShippingLabelKey()).startsWith("labels/shipping/");

        // The shipping label PDF was stored and is retrievable (Req 12.2).
        Optional<StorageService.StoredObject> label = storage.load(savedRecord.getShippingLabelKey());
        assertThat(label).isPresent();
        assertThat(new String(label.get().content(), 0, 5)).startsWith("%PDF-");
    }

    @Test
    void assignmentIsIdempotentWhenOrderNotPacked() {
        OrderEntity order = packedCodOrder();
        order.setOrderStatus(OrderStatus.COURIER_ASSIGNED);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        assignmentService.assignForOrder(10L);

        // No re-assignment: status unchanged and no courier record written.
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COURIER_ASSIGNED);
        assertThat(savedRecord).isNull();
    }

    private OrderEntity packedCodOrder() {
        OrderEntity order = new OrderEntity(
                "SHR-000777", OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.addLineItem(new OrderLineItem(1L, "Neem Capsules", 2,
                new BigDecimal("120.00"), new BigDecimal("240.00")));
        order.applyAmounts(new BigDecimal("240.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("240.00"), new BigDecimal("240.00"), PaymentStatus.COD);
        order.setOrderStatus(OrderStatus.PACKED);
        return order;
    }

    /** In-memory {@link StorageService} that keeps stored bytes for retrieval. */
    private static final class InMemoryStorage implements StorageService {
        private final Map<String, byte[]> objects = new HashMap<>();
        private int seq = 0;

        @Override
        public StoredObjectRef store(String prefix, String originalFilename,
                                     String contentType, byte[] content) {
            String key = prefix + "/" + (seq++) + "-" + originalFilename;
            objects.put(key, content);
            return new StoredObjectRef(key);
        }

        @Override
        public Optional<StoredObject> load(String key) {
            byte[] content = objects.get(key);
            return content == null ? Optional.empty()
                    : Optional.of(new StoredObject(content, "application/pdf", "label.pdf"));
        }
    }
}
