package com.shifa.oms.whatsapp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Persistence for customizable WhatsApp message templates (V44).
 */
public interface WhatsappTemplateRepository extends JpaRepository<WhatsappTemplate, Long> {

    /** Active templates shown to senders, ordered for display. */
    List<WhatsappTemplate> findByActiveTrueOrderBySortOrderAscIdAsc();

    /** Every template (management view), ordered for display. */
    List<WhatsappTemplate> findAllByOrderBySortOrderAscIdAsc();

    /** Whether a template already uses the given key (uniqueness on create). */
    boolean existsByTemplateKey(String templateKey);
}
