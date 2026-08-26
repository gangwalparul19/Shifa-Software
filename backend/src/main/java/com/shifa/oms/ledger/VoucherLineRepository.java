package com.shifa.oms.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository for {@link VoucherLine} (Req 12, 2.4).
 *
 * <p>Backs the Ledger view / running balance (lines for one account), the referenced-ledger delete
 * guard ({@link #existsByLedgerAccountId}, Req 2.4), and loading a voucher's own lines.
 */
public interface VoucherLineRepository extends JpaRepository<VoucherLine, Long> {

    /** All lines posted to a ledger account (Ledger view / delete guard). */
    List<VoucherLine> findByLedgerAccountId(Long ledgerAccountId);

    /**
     * All lines posted to a ledger account whose parent voucher's date falls within {@code [from, to]},
     * in <strong>chronological order</strong> (voucher date, then voucher id, then line order) — the
     * in-period statement lines the Ledger view / running balance consumes (Req 12.1). Joins each line
     * to its {@link Voucher} on {@code voucher_id} to filter and order by the voucher date.
     */
    @Query("select vl from VoucherLine vl, Voucher v "
            + "where v.id = vl.voucherId and vl.ledgerAccountId = :ledgerAccountId "
            + "and v.voucherDate between :from and :to "
            + "order by v.voucherDate asc, v.id asc, vl.lineOrder asc")
    List<VoucherLine> findForLedgerInPeriodChronological(@Param("ledgerAccountId") Long ledgerAccountId,
                                                         @Param("from") LocalDate from,
                                                         @Param("to") LocalDate to);

    /**
     * All lines whose parent voucher's date falls within {@code [from, to]} — every posted line in the
     * period, batch-loaded in a single join query for the Trial Balance's per-account net-movement
     * aggregation (Reqs 14.1–14.4). Grouping by ledger account is done in memory to avoid an N+1.
     */
    @Query("select vl from VoucherLine vl, Voucher v "
            + "where v.id = vl.voucherId and v.voucherDate between :from and :to")
    List<VoucherLine> findForPeriod(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** True when any voucher line references the given ledger account (Req 2.4 delete guard). */
    boolean existsByLedgerAccountId(Long ledgerAccountId);

    /** The lines of a single voucher, in entry order. */
    List<VoucherLine> findByVoucherIdOrderByLineOrderAsc(Long voucherId);

    /**
     * All lines belonging to any of the given vouchers. Used to gather a financial year's posted
     * lines (via that year's voucher ids) for the closing-balance carry-forward (Req 3.4).
     */
    List<VoucherLine> findByVoucherIdIn(Collection<Long> voucherIds);
}
