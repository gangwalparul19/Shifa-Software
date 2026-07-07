package com.shifa.oms.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Spring Data repository for {@link CustomerCartItem}. All operations are scoped
 * by {@code customerId} (the registered customer's user id) so a customer only
 * ever sees or mutates their own saved cart.
 */
public interface CustomerCartItemRepository extends JpaRepository<CustomerCartItem, Long> {

    /** A customer's saved cart lines in insertion order (stable ordering for the client). */
    List<CustomerCartItem> findByCustomerIdOrderByIdAsc(Long customerId);

    /**
     * Removes all of a customer's saved cart lines as an immediate bulk DML
     * delete; used to atomically replace the cart. This runs as a real
     * {@code DELETE} statement (not queued entity removals), so when
     * {@link CustomerCartService#replace} re-inserts the same {@code (customer,
     * product)} rows afterwards, Hibernate's insert-before-delete flush ordering
     * cannot cause a duplicate-key collision. {@code clearAutomatically} evicts
     * any stale cart entities from the persistence context after the delete.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("delete from CustomerCartItem c where c.customerId = :customerId")
    void deleteByCustomerId(@Param("customerId") Long customerId);
}
