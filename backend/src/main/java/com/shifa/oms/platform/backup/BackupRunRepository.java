package com.shifa.oms.platform.backup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link BackupRun} rows.
 *
 * <p>The backup job saves a row when a run starts and updates it on completion;
 * the finders below let the admin surface recent backup history (most recent
 * first) and the newest successful backup for a "last backup" indicator.
 */
public interface BackupRunRepository extends JpaRepository<BackupRun, Long> {

    /** All runs, most recent first (for the admin backup history view). */
    List<BackupRun> findAllByOrderByStartedAtDesc();

    /** The most recent run of a given status, or empty when none exists. */
    java.util.Optional<BackupRun> findFirstByStatusOrderByStartedAtDesc(String status);
}
