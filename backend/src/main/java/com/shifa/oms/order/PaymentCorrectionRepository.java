package com.shifa.oms.order;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for immutable administrator payment-correction records. */
public interface PaymentCorrectionRepository extends JpaRepository<PaymentCorrection, Long> {
}
