package com.shifa.oms.ledger;

import com.shifa.oms.ledger.domain.VoucherType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates stable, per-type-per-financial-year voucher references (Req 5.8).
 *
 * <p><strong>Format.</strong> A reference is {@code <prefix>/<fy label>/<zero-padded number>}, for
 * example {@code JV/2025-26/0001} (Journal) or {@code SAL/2025-26/0007} (Sales), matching the
 * design's Tally-style scheme. The short per-type prefix is centralised in {@link #prefixFor} so it
 * is defined in exactly one place; the financial-year label (for example {@code "2025-26"}) comes
 * from the {@link FinancialYear} itself.
 *
 * <p><strong>Uniqueness / no duplicates.</strong> The running series lives in the
 * {@code ledger_voucher_sequences} table, one row per {@code (voucher_type, financial_year_id)},
 * mirroring the {@code invoice_sequence} / {@code purchase_order_sequence} pattern. {@link #allocate}
 * reads the matching row under a pessimistic write lock and increments it in the same transaction,
 * so concurrent allocations serialize on the row lock and each caller receives a distinct number —
 * references are therefore unique within a voucher type and financial year (Req 5.8). The series is
 * gap-tolerant: an allocated number that is not ultimately persisted simply leaves a gap.
 */
@Service
public class VoucherReferenceSequencer {

    /** Voucher-reference running numbers are zero-padded to at least this many digits. */
    static final int MIN_DIGITS = 4;

    private final LedgerVoucherSequenceRepository sequenceRepository;

    public VoucherReferenceSequencer(LedgerVoucherSequenceRepository sequenceRepository) {
        this.sequenceRepository = sequenceRepository;
    }

    /**
     * Allocates the next voucher reference for a voucher type within a financial year and formats it
     * as {@code <prefix>/<fy label>/<zero-padded number>} (Req 5.8).
     *
     * @param type           the voucher type the reference is for
     * @param financialYear  the financial year the voucher is posted into (supplies the id + label)
     * @return the formatted, unique-within-(type, FY) voucher reference
     */
    @Transactional
    public String allocate(VoucherType type, FinancialYear financialYear) {
        long value = allocateNext(type, financialYear.getId());
        return format(prefixFor(type), financialYear.getLabel(), value);
    }

    /**
     * Allocates the next raw series number for a {@code (type, financialYearId)} atomically under a
     * row lock, seeding the counter row on first use. Never returns the same value to two callers
     * for the same type and financial year.
     *
     * @param type              the voucher type
     * @param financialYearId   the financial year id
     * @return the allocated series number (monotonically increasing per type/FY)
     */
    @Transactional
    public long allocateNext(VoucherType type, long financialYearId) {
        LedgerVoucherSequence sequence = sequenceRepository
                .findByIdForUpdate(type.name(), financialYearId)
                .orElseGet(() -> sequenceRepository.save(
                        new LedgerVoucherSequence(type.name(), financialYearId, 1L)));
        long value = sequence.getNextValue();
        sequence.setNextValue(value + 1L);
        sequenceRepository.save(sequence);
        return value;
    }

    /** Formats {@code <prefix>/<fy label>/<zero-padded value>} (for example {@code JV/2025-26/0001}). */
    static String format(String prefix, String financialYearLabel, long value) {
        return prefix + "/" + financialYearLabel + "/" + String.format("%0" + MIN_DIGITS + "d", value);
    }

    /**
     * The short, Tally-style reference prefix for each voucher type — the single, centralised place
     * these are defined. {@code JV}/{@code SAL} match the design's worked examples; the rest follow
     * the same convention.
     *
     * @param type the voucher type
     * @return its reference prefix
     */
    static String prefixFor(VoucherType type) {
        return switch (type) {
            case JOURNAL -> "JV";
            case PAYMENT -> "PMT";
            case RECEIPT -> "RCT";
            case CONTRA -> "CTR";
            case SALES -> "SAL";
            case PURCHASE -> "PUR";
            case DEBIT_NOTE -> "DN";
            case CREDIT_NOTE -> "CN";
        };
    }
}
