package com.shifa.oms.ledger;

import com.shifa.oms.ledger.domain.VoucherType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Voucher} (Req 5.x, 6.x, 8–13).
 *
 * <p>Backs auto-posting idempotency ({@link #findBySourceTypeAndSourceId}), the Day Book
 * (chronological, in-period, optionally type-filtered), and financial-year scoped listing.
 */
public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    /** The voucher posted for a given source document, if any (auto-posting idempotency). */
    Optional<Voucher> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    /** All vouchers whose date falls within {@code [from, to]}, chronologically (Day Book, Req 13.1). */
    List<Voucher> findByVoucherDateBetweenOrderByVoucherDateAscIdAsc(LocalDate from, LocalDate to);

    /** In-period vouchers of a single type, chronologically (Day Book type filter, Req 13.3). */
    List<Voucher> findByVoucherTypeAndVoucherDateBetweenOrderByVoucherDateAscIdAsc(VoucherType voucherType,
                                                                                   LocalDate from,
                                                                                   LocalDate to);

    /** All vouchers assigned to the given financial year. */
    List<Voucher> findByFinancialYearId(Long financialYearId);

    /** All vouchers of the given type. */
    List<Voucher> findByVoucherType(VoucherType voucherType);
}
