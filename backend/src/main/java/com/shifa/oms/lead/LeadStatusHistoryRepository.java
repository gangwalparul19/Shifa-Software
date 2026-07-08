package com.shifa.oms.lead;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link LeadStatusHistory} (design &sect;Components).
 *
 * <p>History rows are written as part of the {@link LeadEntity} aggregate (via
 * the cascaded {@code @OneToMany}), so this repository is a read helper for the
 * lead-detail view: the ordered status trail for a given lead (Req 3.5).
 */
public interface LeadStatusHistoryRepository extends JpaRepository<LeadStatusHistory, Long> {

    /** The status trail for a lead, oldest change first (Req 3.5). */
    List<LeadStatusHistory> findByLeadIdOrderByIdAsc(Long leadId);
}
