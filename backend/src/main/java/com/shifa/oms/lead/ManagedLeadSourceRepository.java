package com.shifa.oms.lead;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the managed {@link ManagedLeadSource} availability
 * list (CRM expansion Phase 3, Req 18.2).
 */
public interface ManagedLeadSourceRepository extends JpaRepository<ManagedLeadSource, Long> {

    /** All sources in display order (admin CRUD view). */
    List<ManagedLeadSource> findAllByOrderBySortOrderAscIdAsc();

    /** Active sources in display order (capture picker). */
    List<ManagedLeadSource> findByActiveTrueOrderBySortOrderAscIdAsc();

    /** A source by its (case-insensitive) name — enforces unique naming. */
    Optional<ManagedLeadSource> findByNameIgnoreCase(String name);
}
