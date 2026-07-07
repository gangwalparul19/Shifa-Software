package com.shifa.oms.platform.backup;

import com.shifa.oms.platform.backup.BackupService.BackupResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin endpoints for the database backup feature (Req 24.1, 24.2).
 *
 * <p>Restricted to {@code ADMIN}: backups are an administrative concern (Req 5.4).
 * The manual-trigger endpoint runs the same {@link BackupService#runBackup()} the
 * nightly scheduler uses, which is handy for a demo or an on-demand backup; the
 * history endpoint lets the admin review recent runs and confirm the platform is
 * meeting its backup guarantee.
 */
@RestController
@RequestMapping("/api/admin/backups")
@PreAuthorize("hasRole('ADMIN')")
public class BackupController {

    private final BackupService backupService;
    private final BackupRunRepository backupRunRepository;

    public BackupController(BackupService backupService, BackupRunRepository backupRunRepository) {
        this.backupService = backupService;
        this.backupRunRepository = backupRunRepository;
    }

    /** Triggers a backup immediately and returns its outcome (Req 24.1, 24.2). */
    @PostMapping("/run")
    public BackupResult run() {
        return backupService.runBackup();
    }

    /** Lists recent backup runs, most recent first, for the admin history view. */
    @GetMapping
    public List<BackupRunResponse> history() {
        return backupRunRepository.findAllByOrderByStartedAtDesc().stream()
                .map(BackupRunResponse::from)
                .toList();
    }

    /** Read model for a {@code backup_runs} row. */
    public record BackupRunResponse(Long id, LocalDateTime startedAt, LocalDateTime finishedAt,
                                    String status, String objectKey, String error) {
        static BackupRunResponse from(BackupRun run) {
            return new BackupRunResponse(run.getId(), run.getStartedAt(), run.getFinishedAt(),
                    run.getStatus(), run.getObjectKey(), run.getError());
        }
    }
}
