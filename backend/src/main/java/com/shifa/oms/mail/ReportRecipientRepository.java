package com.shifa.oms.mail;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRecipientRepository extends JpaRepository<ReportRecipient, Long> {

    /** Every saved extra address, oldest first (paused ones included). */
    List<ReportRecipient> findAllByOrderByIdAsc();

    /** The addresses that currently receive the reports, oldest first. */
    List<ReportRecipient> findByActiveTrueOrderByIdAsc();

    boolean existsByEmailIgnoreCase(String email);
}
