package com.shifa.oms.invoice;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data repository for the single-row {@link InvoiceSequence} counter.
 */
public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, Long> {

    /**
     * Loads the counter row under a pessimistic write lock so concurrent invoice
     * allocations serialize on the row and never allocate the same number twice.
     *
     * @param id the counter row id ({@link InvoiceSequence#SINGLETON_ID})
     * @return the locked row, if present
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InvoiceSequence s where s.id = :id")
    Optional<InvoiceSequence> findByIdForUpdate(@Param("id") long id);
}
