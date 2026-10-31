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
            WHERE p.utr_normalized = :utr
              AND (:bankAccountId IS NULL OR p.bank_account_id = :bankAccountId)
            """, nativeQuery = true)
    java.util.List<PaymentMatch> findByUtr(@Param("utr") String utr,
                                           @Param("bankAccountId") Long bankAccountId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from OrderPayment p where p.id = :id")
    java.util.Optional<OrderPayment> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    java.util.Optional<Long> findOrderIdById(@Param("id") Long id);

    @Query(value = "SELECT p.order_id AS orderId, p.id AS paymentId, p.amount_received AS amount, p.bank_account_id AS bankAccountId FROM payments p WHERE p.id = :id", nativeQuery = true)
    java.util.Optional<PaymentMatch> findMatchById(@Param("id") Long id);

    interface PaymentMatch {
        Long getPaymentId();
        Long getOrderId();
        java.math.BigDecimal getAmount();
        Long getBankAccountId();
    }

    @Query(value = """
            SELECT p.bank_account_id AS accountId,
                   COALESCE(ba.display_name, 'Unassigned / legacy') AS accountName,
                   COALESCE(SUM(p.amount_received), 0) AS omsBookedAmount,
                   COALESCE(SUM(CASE WHEN o.payment_verification_status = 'VERIFIED' THEN p.amount_received ELSE 0 END), 0) AS verifiedAmount,
                   COALESCE(SUM(CASE WHEN o.payment_verification_status = 'PENDING' THEN p.amount_received ELSE 0 END), 0) AS pendingAmount,
                   COALESCE(SUM(CASE WHEN o.payment_verification_status = 'REJECTED' THEN p.amount_received ELSE 0 END), 0) AS rejectedAmount,
                   COALESCE(SUM(CASE WHEN o.payment_verification_status IS NULL THEN p.amount_received ELSE 0 END), 0) AS unreviewedAmount
            FROM payments p
            JOIN orders o ON o.id = p.order_id AND o.active = 1
            LEFT JOIN bank_accounts ba ON ba.id = p.bank_account_id
            WHERE p.captured_at >= :fromDate
              AND p.captured_at < :toDate
            GROUP BY p.bank_account_id, ba.display_name
            """, nativeQuery = true)
    java.util.List<UpfrontPaymentKpiRow> upfrontPaymentKpisBetween(
            @Param("fromDate") java.time.LocalDateTime fromDate,
            @Param("toDate") java.time.LocalDateTime toDate);

    @Query(value = """
            SELECT p.bank_account_id AS accountId,
                   COALESCE(ba.display_name, 'Unassigned / legacy') AS accountName,
                   COALESCE(SUM(p.amount_received), 0) AS omsBookedAmount,
                   COALESCE(SUM(CASE WHEN o.payment_verification_status = 'VERIFIED' THEN p.amount_received ELSE 0 END), 0) AS verifiedAmount,
                   COALESCE(SUM(CASE WHEN o.payment_verification_status = 'PENDING' THEN p.amount_received ELSE 0 END), 0) AS pendingAmount,
                   COALESCE(SUM(CASE WHEN o.payment_verification_status = 'REJECTED' THEN p.amount_received ELSE 0 END), 0) AS rejectedAmount,
                   COALESCE(SUM(CASE WHEN o.payment_verification_status IS NULL THEN p.amount_received ELSE 0 END), 0) AS unreviewedAmount
            FROM payments p
            JOIN orders o ON o.id = p.order_id AND o.active = 1
            LEFT JOIN bank_accounts ba ON ba.id = p.bank_account_id
            GROUP BY p.bank_account_id, ba.display_name
            """, nativeQuery = true)
    java.util.List<UpfrontPaymentKpiRow> upfrontPaymentKpisAll();

    interface UpfrontPaymentKpiRow {
        Long getAccountId();
        String getAccountName();
        java.math.BigDecimal getOmsBookedAmount();
        java.math.BigDecimal getVerifiedAmount();
        java.math.BigDecimal getPendingAmount();
        java.math.BigDecimal getRejectedAmount();
        java.math.BigDecimal getUnreviewedAmount();
    }

    @Query(value = """
            SELECT COUNT(*) FROM payments
            WHERE bank_account_id = :bankAccountId
              AND utr_normalized = :utr
              AND (:orderId IS NULL OR order_id <> :orderId)
            """, nativeQuery = true)
    long countDuplicateUtr(@Param("bankAccountId") Long bankAccountId,
                           @Param("utr") String utr,
                           @Param("orderId") Long orderId);
}
