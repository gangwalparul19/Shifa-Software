package com.shifa.oms.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link PaymentTransaction}.
 *
 * <p>Confirm resolves the transaction created at initiate by its gateway order
 * id; admin visibility lists an order's attempts newest first.
 */
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /** All transactions for an order, newest first (admin visibility). */
    List<PaymentTransaction> findByOrderIdOrderByCreatedAtDescIdDesc(Long orderId);

    /** The transaction created for a gateway order id, if any (confirm lookup). */
    Optional<PaymentTransaction> findByGatewayOrderId(String gatewayOrderId);

    /** Whether an order already has a verified (PAID) transaction. */
    boolean existsByOrderIdAndStatus(Long orderId, PaymentTransactionStatus status);
}
