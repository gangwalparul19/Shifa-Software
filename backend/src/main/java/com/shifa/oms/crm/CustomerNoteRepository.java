package com.shifa.oms.crm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Persistence for the customer notes timeline (FEATURE-ROADMAP §1.1). Notes are
 * keyed by the customer's mobile (customers are derived data — see
 * {@link CustomerNote}).
 */
public interface CustomerNoteRepository extends JpaRepository<CustomerNote, Long> {

    /** A customer's notes, newest first. */
    List<CustomerNote> findByCustomerMobileOrderByCreatedAtDesc(String customerMobile);
}
