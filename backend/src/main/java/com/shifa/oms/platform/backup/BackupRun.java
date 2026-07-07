package com.shifa.oms.platform.backup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A record of one scheduled database-backup attempt, mapped to the
 * {@code backup_runs} table (design "Daily backup strategy (Req 24)").
 *
 * <p>A row is created when a backup starts ({@code started_at} defaulted by the
 * DB) and finalised when it finishes: {@link #markSuccess(String)} records the
 * Object Storage key of the uploaded dump and status {@code SUCCESS}, while
 * {@link #markFailed(String)} records the error text and status {@code FAILED}
 * (Req 24.2). The row is the durable audit trail of whether the platform met its
 * "at least one backup every 24h" guarantee (Req 24.1).
 */
@Entity
@Table(name = "backup_runs")
public class BackupRun {

    /** Status for a backup that completed and uploaded its archive successfully. */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** Status for a backup that failed at dump, gzip, or upload (Req 24.2). */
    public static final String STATUS_FAILED = "FAILED";

    /** Transient status while a run is in progress (never persisted as terminal). */
    public static final String STATUS_RUNNING = "RUNNING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "error", length = 1000)
    private String error;

    protected BackupRun() {
        // Required by JPA.
    }

    /** Starts a new run at {@code now} in the {@code RUNNING} state. */
    public static BackupRun started(LocalDateTime now) {
        BackupRun run = new BackupRun();
        run.startedAt = now;
        run.status = STATUS_RUNNING;
        return run;
    }

    /**
     * Marks this run successful: status {@code SUCCESS}, finished now, storing the
     * uploaded archive's Object Storage key (Req 24.1).
     */
    public void markSuccess(String objectKey) {
        this.status = STATUS_SUCCESS;
        this.objectKey = objectKey;
        this.error = null;
        this.finishedAt = LocalDateTime.now();
    }

    /**
     * Marks this run failed: status {@code FAILED}, finished now, retaining the
     * error text so the admin can diagnose the failure (Req 24.2).
     */
    public void markFailed(String error) {
        this.status = STATUS_FAILED;
        this.error = truncate(error);
        this.finishedAt = LocalDateTime.now();
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getError() {
        return error;
    }
}
