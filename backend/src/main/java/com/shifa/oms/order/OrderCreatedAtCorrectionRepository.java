package com.shifa.oms.order;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for immutable order punched-date corrections. */
public interface OrderCreatedAtCorrectionRepository extends JpaRepository<OrderCreatedAtCorrection, Long> {
}
