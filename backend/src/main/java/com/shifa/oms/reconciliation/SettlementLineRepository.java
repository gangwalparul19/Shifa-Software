package com.shifa.oms.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SettlementLineRepository extends JpaRepository<SettlementLine, Long> {
    List<SettlementLine> findByBatchIdOrderBySourceRowNumberAsc(Long batchId);

    @Query(value = """
            SELECT b.destination_account_id AS accountId,
                   ba.display_name AS accountName,
                   COALESCE(SUM(l.amount), 0) AS statementAmount,
                   COALESCE(SUM(l.allocated_amount), 0) AS matchedAmount,
                   COALESCE(SUM(l.suspense_amount), 0) AS unmatchedAmount,
                   COUNT(*) AS statementRows,
                   SUM(CASE WHEN l.match_status IN ('MATCHED', 'RESOLVED') THEN 1 ELSE 0 END) AS matchedRows,
                   SUM(CASE WHEN l.suspense_amount > 0 THEN 1 ELSE 0 END) AS unmatchedRows
            FROM settlement_lines l
            JOIN settlement_batches b ON b.id = l.batch_id
            JOIN bank_accounts ba ON ba.id = b.destination_account_id
            WHERE b.source_channel = 'BANK_RECEIPT'
              AND b.destination_account_id IS NOT NULL
              AND COALESCE(l.transaction_date, b.statement_date) >= :fromDate
              AND COALESCE(l.transaction_date, b.statement_date) < :toDate
            GROUP BY b.destination_account_id, ba.display_name
            """, nativeQuery = true)
    List<BankReceiptKpiRow> bankReceiptKpisBetween(@Param("fromDate") java.time.LocalDate fromDate,
                                                   @Param("toDate") java.time.LocalDate toDate);

    @Query(value = """
            SELECT b.destination_account_id AS accountId,
                   ba.display_name AS accountName,
                   COALESCE(SUM(l.amount), 0) AS statementAmount,
                   COALESCE(SUM(l.allocated_amount), 0) AS matchedAmount,
                   COALESCE(SUM(l.suspense_amount), 0) AS unmatchedAmount,
                   COUNT(*) AS statementRows,
                   SUM(CASE WHEN l.match_status IN ('MATCHED', 'RESOLVED') THEN 1 ELSE 0 END) AS matchedRows,
                   SUM(CASE WHEN l.suspense_amount > 0 THEN 1 ELSE 0 END) AS unmatchedRows
            FROM settlement_lines l
            JOIN settlement_batches b ON b.id = l.batch_id
            JOIN bank_accounts ba ON ba.id = b.destination_account_id
            WHERE b.source_channel = 'BANK_RECEIPT'
              AND b.destination_account_id IS NOT NULL
            GROUP BY b.destination_account_id, ba.display_name
            """, nativeQuery = true)
    List<BankReceiptKpiRow> bankReceiptKpisAll();

    interface BankReceiptKpiRow {
        Long getAccountId();
        String getAccountName();
        java.math.BigDecimal getStatementAmount();
        java.math.BigDecimal getMatchedAmount();
        java.math.BigDecimal getUnmatchedAmount();
        Number getStatementRows();
        Number getMatchedRows();
        Number getUnmatchedRows();
    }
}
