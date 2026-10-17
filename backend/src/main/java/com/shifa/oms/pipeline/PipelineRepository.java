package com.shifa.oms.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the configurable {@link Pipeline} (CRM expansion
 * Phase 3, Req 16).
 */
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    /** All pipelines in display order (sort then id). */
    List<Pipeline> findAllByOrderBySortOrderAscIdAsc();

    /** The active pipelines, in display order — for the board / capture picker. */
    List<Pipeline> findByActiveTrueOrderBySortOrderAscIdAsc();

    /** The seeded Default pipeline (there is exactly one). */
    Optional<Pipeline> findFirstByIsDefaultTrue();

    /** A pipeline by its (case-insensitive) name — enforces unique naming. */
    Optional<Pipeline> findByNameIgnoreCase(String name);
}
