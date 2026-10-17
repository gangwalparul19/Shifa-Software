package com.shifa.oms.crmsettings;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the single-row {@link CrmSettings} (CRM expansion
 * Phase 3, Req 18.3).
 */
public interface CrmSettingsRepository extends JpaRepository<CrmSettings, Byte> {
}
