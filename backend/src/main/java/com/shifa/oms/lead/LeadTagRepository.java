package com.shifa.oms.lead;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the managed {@link LeadTag} vocabulary (CRM
 * expansion, design &sect;1.1, Req 4).
 */
public interface LeadTagRepository extends JpaRepository<LeadTag, Long> {

    /** Active tags for the picker, in display order then id. */
    List<LeadTag> findByActiveTrueOrderBySortOrderAscIdAsc();

    /** A tag by its (case-insensitive) name — backs the bulk-import tag matching. */
    Optional<LeadTag> findByNameIgnoreCase(String name);
}
