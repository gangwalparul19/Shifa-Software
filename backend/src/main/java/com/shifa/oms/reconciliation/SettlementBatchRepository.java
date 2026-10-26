package com.shifa.oms.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {
    Optional<SettlementBatch> findByFileHash(String fileHash);
    Optional<SettlementBatch> findBySourceChannelAndExternalReference(SettlementSource source, String reference);
    List<SettlementBatch> findTop100ByOrderByCreatedAtDescIdDesc();
}
