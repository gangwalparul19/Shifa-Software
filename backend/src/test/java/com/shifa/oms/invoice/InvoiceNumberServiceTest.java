package com.shifa.oms.invoice;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.AppSettingsRepository;
import com.shifa.oms.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvoiceNumberService} (Wave 3, Feature 1). Repositories
 * are mocked interfaces; {@link SettingsService} is a real instance over a mocked
 * repository (it is a concrete class and not mockable on this JVM). A single
 * stateful {@link InvoiceSequence} backs the counter so sequential allocations
 * genuinely increment.
 *
 * <p>Covers: allocation is sequential with no duplicates; the invoice number is
 * prefixed + zero-padded; {@code assignIfAbsent} allocates once and is idempotent
 * on subsequent calls (stable re-downloads).
 */
@ExtendWith(MockitoExtension.class)
class InvoiceNumberServiceTest {

    @Mock
    private InvoiceSequenceRepository sequenceRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private AppSettingsRepository appSettingsRepository;

    private InvoiceNumberService service;

    @BeforeEach
    void setUp() {
        // A single shared, mutable counter row so allocate() genuinely increments.
        InvoiceSequence sequence = new InvoiceSequence(InvoiceSequence.SINGLETON_ID, 1L);
        lenient().when(sequenceRepository.findByIdForUpdate(InvoiceSequence.SINGLETON_ID))
                .thenReturn(Optional.of(sequence));
        lenient().when(sequenceRepository.save(any(InvoiceSequence.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderRepository.save(any(OrderEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AppSettings settings = new AppSettings();
        settings.setInvoiceNumberPrefix("SHR/24-25/");
        lenient().when(appSettingsRepository.findById(AppSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings));

        service = new InvoiceNumberService(sequenceRepository, orderRepository,
                new SettingsService(appSettingsRepository));
    }

    @Test
    void allocateReturnsSequentialValuesWithNoDuplicates() {
        Set<Long> seen = new HashSet<>();
        long previous = 0L;
        for (int i = 0; i < 50; i++) {
            long value = service.allocate();
            assertThat(seen.add(value)).as("value %s must be unique", value).isTrue();
            assertThat(value).isGreaterThan(previous);
            previous = value;
        }
        assertThat(seen).hasSize(50);
    }

    @Test
    void formatAppliesPrefixAndZeroPadding() {
        assertThat(InvoiceNumberService.format("SHR/24-25/", 1L)).isEqualTo("SHR/24-25/0001");
        assertThat(InvoiceNumberService.format("SHR/24-25/", 42L)).isEqualTo("SHR/24-25/0042");
        assertThat(InvoiceNumberService.format("SHR/24-25/", 12345L)).isEqualTo("SHR/24-25/12345");
        // A blank prefix yields just the zero-padded number.
        assertThat(InvoiceNumberService.format("  ", 7L)).isEqualTo("0007");
        assertThat(InvoiceNumberService.format(null, 7L)).isEqualTo("0007");
    }

    @Test
    void assignIfAbsentAllocatesOnFirstUseAndIsIdempotent() {
        OrderEntity order = order(10L);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        String first = service.assignIfAbsent(10L);
        assertThat(first).isEqualTo("SHR/24-25/0001");
        assertThat(order.getInvoiceNumber()).isEqualTo("SHR/24-25/0001");

        // Second call reuses the persisted number and does NOT allocate again.
        String second = service.assignIfAbsent(10L);
        assertThat(second).isEqualTo("SHR/24-25/0001");
        // Counter was only touched once (single allocation).
        verify(sequenceRepository, times(1)).findByIdForUpdate(anyLong());
    }

    @Test
    void assignIfAbsentGivesDistinctNumbersToDistinctOrders() {
        OrderEntity a = order(1L);
        OrderEntity b = order(2L);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(a));
        when(orderRepository.findById(2L)).thenReturn(Optional.of(b));

        String na = service.assignIfAbsent(1L);
        String nb = service.assignIfAbsent(2L);

        assertThat(na).isEqualTo("SHR/24-25/0001");
        assertThat(nb).isEqualTo("SHR/24-25/0002");
        assertThat(na).isNotEqualTo(nb);
    }

    private OrderEntity order(long id) {
        OrderEntity order = new OrderEntity("SHR-00" + id, OrderSource.SALESPERSON, 7L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }
}
