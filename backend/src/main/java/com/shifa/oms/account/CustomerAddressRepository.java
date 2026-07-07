package com.shifa.oms.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link CustomerAddress}. All finders are scoped by
 * {@code userId} so the service can enforce that a customer only ever reads or
 * mutates their own address book entries.
 */
public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    /** A user's addresses, defaults first then newest first. */
    List<CustomerAddress> findByUserIdOrderByIsDefaultDescCreatedAtDesc(Long userId);

    /** A single address by id, but only when it belongs to the given user (ownership guard). */
    Optional<CustomerAddress> findByIdAndUserId(Long id, Long userId);

    /** All of a user's addresses (used to clear existing defaults when a new default is set). */
    List<CustomerAddress> findByUserId(Long userId);
}
