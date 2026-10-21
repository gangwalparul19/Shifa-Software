package com.shifa.oms.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Payment-transaction queries used by reconciliation safety checks. */
public interface OrderPaymentRepository extends JpaRepository<OrderPayment, Long> {

    @Query(value = """
            SELECT p.id AS paymentId, p.order_id AS orderId, p.amount_received AS amount,
                   p.bank_account_id AS bankAccountId
            FROM payments p
            WHERE UPPER(TRIM(p.utr)) = UPPER(TRIM(:utr))
              AND (:bankAccountId IS NULL OR p.bank_account_id = :bankAccountId)
            """, nativeQuery = true)
    java.util.List<PaymentMatch> findByUtr(@Param("utr") String utr,
                                           @Param("bankAccountId") Long bankAccountId);

    interface PaymentMatch {
        Long getPaymentId();
        Long getOrderId();
        java.math.BigDecimal getAmount();
        Long getBankAccountId();
    }

    @Query(value = """
            SELECT COUNT(*) FROM payments
            WHERE bank_account_id = :bankAccountId
              AND UPPER(TRIM(utr)) = UPPER(TRIM(:utr))
              AND (:orderId IS NULL OR order_id <> :orderId)
            """, nativeQuery = true)
    long countDuplicateUtr(@Param("bankAccountId") Long bankAccountId,
                           @Param("utr") String utr,
                           @Param("orderId") Long orderId);
}
