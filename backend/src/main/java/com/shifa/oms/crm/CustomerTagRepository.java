package com.shifa.oms.crm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Persistence for customer segment tags (FEATURE-ROADMAP §1.4). Tags are keyed
 * by the customer's mobile (customers are derived data — see {@link CustomerTag}).
 */
public interface CustomerTagRepository extends JpaRepository<CustomerTag, Long> {

    /** A customer's tags, alphabetical. */
    List<CustomerTag> findByCustomerMobileOrderByTagAsc(String customerMobile);

    /** Whether the customer already carries this tag (case-insensitive, avoids dupes). */
    boolean existsByCustomerMobileAndTagIgnoreCase(String customerMobile, String tag);

    /** Removes a specific tag from a customer; returns the number of rows deleted. */
    long deleteByCustomerMobileAndTag(String customerMobile, String tag);
}
