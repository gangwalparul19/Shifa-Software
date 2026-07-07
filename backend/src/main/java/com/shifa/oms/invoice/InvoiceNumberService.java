package com.shifa.oms.invoice;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates stable, sequential invoice numbers (Wave 3, Feature 1).
 *
 * <p><strong>When.</strong> An invoice number is allocated lazily on the first
 * invoice generation for an order (not at order creation), so numbers are only
 * consumed by orders that are actually invoiced, and are assigned in the order
 * invoices are first produced. Once allocated, the number is persisted on the
 * order ({@code orders.invoice_number}) and reused on every subsequent PDF
 * fetch, so re-downloads are stable and a number is never re-allocated.
 *
 * <p><strong>Atomicity / no duplicates.</strong> The running series lives in the
 * single-row {@code invoice_sequence} table. {@link #allocate()} reads that row
 * under a pessimistic write lock and increments it in the same transaction, so
 * concurrent allocations serialize on the row lock and each receives a distinct
 * value.
 *
 * <p>The final number is {@code <prefix><zero-padded value>} using the
 * configurable {@link AppSettings#getInvoiceNumberPrefix()} (a blank prefix
 * yields just the number).
 */
@Service
public class InvoiceNumberService {

    /** Invoice numbers are zero-padded to at least this many digits. */
    static final int MIN_DIGITS = 4;

    private final InvoiceSequenceRepository sequenceRepository;
    private final OrderRepository orderRepository;
    private final SettingsService settingsService;

    public InvoiceNumberService(InvoiceSequenceRepository sequenceRepository,
                                OrderRepository orderRepository,
                                SettingsService settingsService) {
        this.sequenceRepository = sequenceRepository;
        this.orderRepository = orderRepository;
        this.settingsService = settingsService;
    }

    /**
     * Returns the order's invoice number, allocating and persisting one on first
     * use. Idempotent: an order that already has an invoice number keeps it.
     *
     * @param orderId the order to assign an invoice number to
     * @return the stable invoice number for the order
     * @throws ResourceNotFoundException if no order has the given id
     */
    @Transactional
    public String assignIfAbsent(Long orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " does not exist."));
        String existing = order.getInvoiceNumber();
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String number = format(nextPrefix(), allocate());
        order.setInvoiceNumber(number);
        orderRepository.save(order);
        return number;
    }

    /**
     * Allocates the next raw series value atomically under a row lock, seeding the
     * counter row on first use. Never returns the same value to two callers.
     *
     * @return the allocated series value (monotonically increasing)
     */
    @Transactional
    public long allocate() {
        InvoiceSequence sequence = sequenceRepository.findByIdForUpdate(InvoiceSequence.SINGLETON_ID)
                .orElseGet(() -> sequenceRepository.save(
                        new InvoiceSequence(InvoiceSequence.SINGLETON_ID, 1L)));
        long value = sequence.getNextValue();
        sequence.setNextValue(value + 1L);
        sequenceRepository.save(sequence);
        return value;
    }

    /** Formats {@code <prefix><zero-padded value>}; a null/blank prefix yields just the number. */
    static String format(String prefix, long value) {
        String number = String.format("%0" + MIN_DIGITS + "d", value);
        return (prefix != null && !prefix.isBlank()) ? prefix + number : number;
    }

    private String nextPrefix() {
        return settingsService.getSettings().getInvoiceNumberPrefix();
    }
}
