package com.shifa.oms.platform.backup;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.platform.backup.BackupService.BackupResult;
import com.shifa.oms.platform.storage.StorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final StorageService storageService;

    public BackupController(BackupService backupService, BackupRunRepository backupRunRepository,
                            StorageService storageService) {
        this.backupService = backupService;
        this.backupRunRepository = backupRunRepository;
        this.storageService = storageService;
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

    /**
     * Streams a successful backup's archive for download (ADMIN only). Resolves
     * the {@code backup_runs} row, fetches the gzip archive from the configured
     * {@link StorageService} (S3 in production) by its stored key, and returns it
     * as an attachment. A run that never succeeded (no archive key) or whose
     * archive is no longer present yields a 404.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        BackupRun run = backupRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Backup run " + id + " does not exist."));
        String key = run.getObjectKey();
        if (!"SUCCESS".equalsIgnoreCase(run.getStatus()) || key == null || key.isBlank()) {
            throw new ResourceNotFoundException("Backup run " + id + " has no downloadable archive.");
        }
        StorageService.StoredObject object = storageService.load(key)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "The backup archive for run " + id + " is no longer available."));
        String filename = downloadName(run);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/gzip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(object.content());
    }

    /** A friendly download filename derived from the run's date, e.g. {@code shifa-backup-2026-07-24.sql.gz}. */
    private static String downloadName(BackupRun run) {
        LocalDateTime when = run.getFinishedAt() != null ? run.getFinishedAt() : run.getStartedAt();
        String stamp = when != null ? when.toLocalDate().toString() : String.valueOf(run.getId());
        return "shifa-backup-" + stamp + ".sql.gz";
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
