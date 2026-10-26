package com.shifa.oms.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementLineRepository extends JpaRepository<SettlementLine, Long> {
    List<SettlementLine> findByBatchIdOrderBySourceRowNumberAsc(Long batchId);
}
