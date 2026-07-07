package com.shifa.oms.settings;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the single-row {@link AppSettings}. The row is
 * always accessed by its fixed {@link AppSettings#SINGLETON_ID}.
 */
public interface AppSettingsRepository extends JpaRepository<AppSettings, Long> {
}
