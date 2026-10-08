package com.shifa.oms.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data repository for the per-type-per-FY {@link LedgerVoucherSequence} counter.
 */
public interface LedgerVoucherSequenceRepository
        extends JpaRepository<LedgerVoucherSequence, LedgerVoucherSequence.Key> {

    /**
     * Loads the counter row for a voucher type and financial year under a pessimistic write lock so
     * concurrent voucher-reference allocations serialize on the row and never allocate the same
     * reference twice within a type and financial year (Req 5.8).
     *
     * @param voucherType      the {@code VoucherType.name()} of the series
     * @param financialYearId  the financial year the series belongs to
     * @return the locked row, if present
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from LedgerVoucherSequence s "
            + "where s.voucherType = :voucherType and s.financialYearId = :financialYearId")
    Optional<LedgerVoucherSequence> findByIdForUpdate(@Param("voucherType") String voucherType,
                                                      @Param("financialYearId") long financialYearId);
}
