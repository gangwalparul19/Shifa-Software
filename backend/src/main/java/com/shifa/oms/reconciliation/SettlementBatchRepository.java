package com.shifa.oms.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {
    Optional<SettlementBatch> findByFileHash(String fileHash);
    Optional<SettlementBatch> findBySourceChannelAndExternalReference(SettlementSource source, String reference);
    List<SettlementBatch> findTop100ByOrderByCreatedAtDescIdDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SettlementBatch b where b.id = :id")
    Optional<SettlementBatch> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
