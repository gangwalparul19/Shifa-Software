package com.shifa.oms.procurement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link PurchaseOrder} aggregates (Feature C2).
 *
 * <p>The filtered finder backs {@code GET /api/admin/purchase-orders}: every
 * filter is optional (a {@code null} disables that clause). {@code q} matches
 * the PO number case-insensitively.
 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    /** True when a PO already uses this generated number. */
    boolean existsByPoNumber(String poNumber);

    /**
     * Filtered, paged PO listing. All parameters are optional:
     * <ul>
     *   <li>{@code status} — exact status match;</li>
     *   <li>{@code supplierId} — exact supplier match;</li>
     *   <li>{@code q} — case-insensitive substring over the PO number.</li>
     * </ul>
     */
    @Query("""
            SELECT po FROM PurchaseOrder po
            WHERE (:status IS NULL OR po.status = :status)
              AND (:supplierId IS NULL OR po.supplierId = :supplierId)
              AND (:q IS NULL OR LOWER(po.poNumber) LIKE CONCAT('%', LOWER(:q), '%'))
            """)
    Page<PurchaseOrder> search(@Param("status") PurchaseOrderStatus status,
                               @Param("supplierId") Long supplierId,
                               @Param("q") String q,
                               Pageable pageable);
}
