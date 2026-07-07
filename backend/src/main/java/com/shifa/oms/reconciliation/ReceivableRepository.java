package com.shifa.oms.reconciliation;

import com.shifa.oms.reconciliation.domain.ReceivableType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * Spring Data repository for {@link ReceivableEntity} rows.
 *
 * <p>Settlement side effects (task 14) create receivables here; the
 * reconciliation dashboard (task 16) reads and settles them. The finders below
 * support idempotent creation (a given order + type should yield one receivable
 * even if a delivered/lost webhook is redelivered).
 */
public interface ReceivableRepository extends JpaRepository<ReceivableEntity, Long>,
        JpaSpecificationExecutor<ReceivableEntity> {

    /** Existing receivables of a type for an order (used to avoid duplicates on redelivery). */
    List<ReceivableEntity> findByOrderIdAndType(Long orderId, ReceivableType type);

    /** All receivables for a courier company. */
    List<ReceivableEntity> findByCourierCompanyId(Long courierCompanyId);

    /** All receivables, newest first — backs the reconciliation listing (Req 18.1&ndash;18.3). */
    List<ReceivableEntity> findAllByOrderByCreatedAtDescIdDesc();

    /** All receivables of a given type, newest first. */
    List<ReceivableEntity> findByTypeOrderByCreatedAtDescIdDesc(ReceivableType type);

    /** All unsettled receivables of a given type, newest first (pending claims / unsettled COD). */
    List<ReceivableEntity> findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(ReceivableType type);

    /**
     * All receivables created within an inclusive timestamp window, used by the
     * P&amp;L report (Feature C3) to derive courier/claim costs from the
     * reconciliation ledger.
     */
    List<ReceivableEntity> findByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
}
