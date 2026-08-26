package com.shifa.oms.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link OpeningBalance} (Req 3.x).
 *
 * <p>Backs the FY opening-balance balancing check (all openings for a year) and per-account opening
 * lookup used by the Ledger view and Trial Balance.
 */
public interface OpeningBalanceRepository extends JpaRepository<OpeningBalance, Long> {

    /** All opening balances recorded for the given financial year. */
    List<OpeningBalance> findByFinancialYearId(Long financialYearId);

    /** The single opening balance for a ledger account in a financial year, if any. */
    Optional<OpeningBalance> findByLedgerAccountIdAndFinancialYearId(Long ledgerAccountId, Long financialYearId);
}
