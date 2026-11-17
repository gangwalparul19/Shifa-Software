package com.shifa.oms.order;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for accounting links created by punched-date corrections. */
public interface OrderCreatedAtLedgerCorrectionRepository
        extends JpaRepository<OrderCreatedAtLedgerCorrection, Long> {
}
