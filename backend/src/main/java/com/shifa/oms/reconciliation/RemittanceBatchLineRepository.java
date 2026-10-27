package com.shifa.oms.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data repository for persisted remittance batch lines (V99). */
public interface RemittanceBatchLineRepository extends JpaRepository<RemittanceBatchLine, Long> {

    /** All lines for a batch, in file order — backs the per-batch details report. */
    List<RemittanceBatchLine> findByBatchIdOrderBySourceRowNumberAsc(Long batchId);

    /** Lines for a set of batches (bulk detail load for a date-range report). */
    List<RemittanceBatchLine> findByBatchIdInOrderByBatchIdDescSourceRowNumberAsc(List<Long> batchIds);
}
