package com.shifa.oms.lead;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository for lead &harr; tag assignments (CRM expansion,
 * design &sect;1.1, Req 4).
 */
public interface LeadTagAssignmentRepository extends JpaRepository<LeadTagAssignment, Long> {

    /** The tag assignments on a lead (for the detail view / tag chips). */
    List<LeadTagAssignment> findByLeadId(Long leadId);

    /** The tag assignments on any of the given leads — batch-loads chips for a list. */
    List<LeadTagAssignment> findByLeadIdIn(Collection<Long> leadIds);

    /** Whether a lead already carries a tag (idempotent add). */
    boolean existsByLeadIdAndTagId(Long leadId, Long tagId);

    /** Removes a tag from a lead; returns the number of rows deleted. */
    @Modifying
    int deleteByLeadIdAndTagId(Long leadId, Long tagId);
}
