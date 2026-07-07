package com.shifa.oms.procurement;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data repository for the single-row {@link PurchaseOrderSequence}
 * counter.
 */
public interface PurchaseOrderSequenceRepository extends JpaRepository<PurchaseOrderSequence, Long> {

    /**
     * Loads the counter row under a pessimistic write lock so concurrent PO
     * creations serialize on the row and never allocate the same number twice.
     *
     * @param id the counter row id ({@link PurchaseOrderSequence#SINGLETON_ID})
     * @return the locked row, if present
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PurchaseOrderSequence s where s.id = :id")
    Optional<PurchaseOrderSequence> findByIdForUpdate(@Param("id") long id);
}
