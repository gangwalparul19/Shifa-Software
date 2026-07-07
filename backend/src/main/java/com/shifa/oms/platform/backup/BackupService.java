package com.shifa.oms.platform.backup;

import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.zip.GZIPOutputStream;

/**
 * Orchestrates the nightly database backup (Req 24.1, 24.2).
 *
 * <p>{@link #runBackup()} records a {@code backup_runs} row, obtains a SQL dump
 * from the injected {@link DatabaseDumpRunner}, gzips it, and uploads the archive
 * to the {@link StorageService} under {@code backups/{yyyy-MM-dd}.sql.gz}. On
 * success the row is finalised {@code SUCCESS} with the stored object key
 * (Req 24.1).
 *
 * <p>On <em>any</em> failure — dump error, gzip error, or upload error — the row
 * is finalised {@code FAILED} with the error text and a {@code BACKUP_FAILED}
 * admin notification is published to the outbox, which the admin SSE stream
 * surfaces on the dashboard (Req 24.2). {@code runBackup()} never propagates the
 * failure to its scheduled caller: it returns a {@link BackupResult} describing
 * the outcome, so one failed backup does not crash the scheduler.
 *
 * <p>The orchestration is deliberately separated from process execution
 * ({@link DatabaseDumpRunner}) and storage ({@link StorageService}) so it is
 * fully unit-testable with mocks and no real {@code mysqldump} or database.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final String STORAGE_PREFIX = "backups";

    private final DatabaseDumpRunner dumpRunner;
    private final StorageService storageService;
    private final BackupRunRepository backupRunRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    public BackupService(DatabaseDumpRunner dumpRunner,
                         StorageService storageService,
                         BackupRunRepository backupRunRepository,
                         OutboxEventPublisher outboxEventPublisher) {
        this.dumpRunner = dumpRunner;
        this.storageService = storageService;
        this.backupRunRepository = backupRunRepository;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * Runs one backup end-to-end (Req 24.1), recording the outcome in
     * {@code backup_runs} and, on failure, notifying the admin (Req 24.2).
     *
     * @return the outcome of this run (never {@code null}); {@link BackupResult#success()}
     *         indicates whether the archive was produced and uploaded
     */
    public BackupResult runBackup() {
        LocalDate today = LocalDate.now();
        String objectName = today + ".sql.gz";
        BackupRun run = backupRunRepository.save(BackupRun.started(LocalDateTime.now()));

        try {
            byte[] sql = dumpRunner.dump();
            byte[] gzipped = gzip(sql);
            StorageService.StoredObjectRef ref = storageService.store(
                    STORAGE_PREFIX, objectName, "application/gzip", gzipped);

            run.markSuccess(ref.key());
            backupRunRepository.save(run);
            log.info("Database backup {} succeeded ({} bytes) → {}",
                    today, gzipped.length, ref.key());
            return BackupResult.success(run.getId(), ref.key());
        } catch (RuntimeException e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            run.markFailed(error);
            backupRunRepository.save(run);
            // Notify the admin of the backup failure (Req 24.2). Persisted in the
            // outbox so the admin SSE stream surfaces it even if no admin is connected.
            outboxEventPublisher.publishBackupFailed(run.getId(), today.toString(), error);
            log.error("Database backup {} failed: {}", today, error);
            return BackupResult.failure(run.getId(), error);
        }
    }

    private static byte[] gzip(byte[] content) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
            gzip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to gzip database dump", e);
        }
    }

    /**
     * Outcome of a backup run.
     *
     * @param success   whether the dump was produced, gzipped, and uploaded
     * @param backupRunId the id of the recorded {@code backup_runs} row
     * @param objectKey the uploaded archive's storage key (null on failure)
     * @param error     the failure detail (null on success)
     */
    public record BackupResult(boolean success, Long backupRunId, String objectKey, String error) {

        static BackupResult success(Long backupRunId, String objectKey) {
            return new BackupResult(true, backupRunId, objectKey, null);
        }

        static BackupResult failure(Long backupRunId, String error) {
            return new BackupResult(false, backupRunId, null, error);
        }
    }
}
