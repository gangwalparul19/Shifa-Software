package com.shifa.oms.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementAllocationRepository extends JpaRepository<SettlementAllocation, Long> {
    java.util.Optional<SettlementAllocation> findBySettlementLineIdAndTargetKey(Long lineId, String targetKey);
    java.util.List<SettlementAllocation> findBySettlementLineId(Long lineId);
    java.util.List<SettlementAllocation> findBySettlementLineIdOrderByAllocatedAtDescIdDesc(Long lineId);
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(a.amount), 0) from SettlementAllocation a where a.targetKey = :key")
    java.math.BigDecimal sumByTargetKey(@org.springframework.data.repository.query.Param("key") String key);
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(a.amount), 0) from SettlementAllocation a where a.settlementLine.id = :lineId")
    java.math.BigDecimal sumByLineId(@org.springframework.data.repository.query.Param("lineId") Long lineId);
}
