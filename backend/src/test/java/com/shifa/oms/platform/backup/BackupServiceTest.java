package com.shifa.oms.platform.backup;

import com.shifa.oms.platform.backup.BackupService.BackupResult;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.support.CronExpression;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration/smoke tests for the scheduled backup job (Req 24.1, 24.2), with the
 * dump runner and Object Storage mocked so no real {@code mysqldump} or database
 * is touched (a real {@link OutboxEventPublisher} over a mocked repository mirrors
 * {@code PackingServiceTest}).
 *
 * <ul>
 *   <li><b>success</b> → a {@code SUCCESS} {@code backup_runs} row is recorded
 *       with the uploaded archive's object key, and a gzip archive is uploaded
 *       under {@code backups/} (Req 24.1);</li>
 *   <li><b>dump failure</b> → a {@code FAILED} row is recorded with the error text
 *       and a {@code BACKUP_FAILED} admin notification is emitted (Req 24.2);</li>
 *   <li><b>upload failure</b> → likewise {@code FAILED} + {@code BACKUP_FAILED}
 *       (Req 24.2);</li>
 *   <li>the configured schedule fires at most every 24h (Req 24.1).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BackupServiceTest {

    @Mock
    private DatabaseDumpRunner dumpRunner;

    @Mock
    private StorageService storageService;

    @Mock
    private BackupRunRepository backupRunRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private BackupService service;

    @BeforeEach
    void setUp() {
        OutboxEventPublisher publisher = new OutboxEventPublisher(outboxEventRepository);
        service = new BackupService(dumpRunner, storageService, backupRunRepository, publisher);
        lenient().when(backupRunRepository.save(any(BackupRun.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // --- Success (Req 24.1) --------------------------------------------------

    @Test
    void successfulRunRecordsSuccessRowWithObjectKeyAndUploadsGzipArchive() throws IOException {
        byte[] sql = "-- SQL DUMP --\nCREATE TABLE t (id INT);".getBytes();
        when(dumpRunner.dump()).thenReturn(sql);
        when(storageService.store(eq("backups"), any(), eq("application/gzip"), any()))
                .thenReturn(new StorageService.StoredObjectRef("backups/2024-05-01.sql.gz"));

        BackupResult result = service.runBackup();

        assertThat(result.success()).isTrue();
        assertThat(result.objectKey()).isEqualTo("backups/2024-05-01.sql.gz");
        assertThat(result.error()).isNull();

        // The uploaded content is a gzip archive of the dump bytes.
        ArgumentCaptor<byte[]> uploaded = ArgumentCaptor.forClass(byte[].class);
        verify(storageService).store(eq("backups"), any(), eq("application/gzip"), uploaded.capture());
        assertThat(gunzip(uploaded.getValue())).isEqualTo(sql);

        // A SUCCESS backup_runs row is recorded with the object key (Req 24.1).
        ArgumentCaptor<BackupRun> runCaptor = ArgumentCaptor.forClass(BackupRun.class);
        verify(backupRunRepository, org.mockito.Mockito.atLeastOnce()).save(runCaptor.capture());
        BackupRun finalRun = runCaptor.getAllValues().get(runCaptor.getAllValues().size() - 1);
        assertThat(finalRun.getStatus()).isEqualTo(BackupRun.STATUS_SUCCESS);
        assertThat(finalRun.getObjectKey()).isEqualTo("backups/2024-05-01.sql.gz");
        assertThat(finalRun.getFinishedAt()).isNotNull();

        // No admin failure notification on success.
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    // --- Dump failure (Req 24.2) --------------------------------------------

    @Test
    void dumpFailureRecordsFailedRowAndEmitsBackupFailedNotification() {
        when(dumpRunner.dump())
                .thenThrow(new DatabaseDumpRunner.DatabaseDumpException("mysqldump exited with code 2"));

        BackupResult result = service.runBackup();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("mysqldump exited with code 2");

        // FAILED backup_runs row with the error text (Req 24.2).
        ArgumentCaptor<BackupRun> runCaptor = ArgumentCaptor.forClass(BackupRun.class);
        verify(backupRunRepository, org.mockito.Mockito.atLeastOnce()).save(runCaptor.capture());
        BackupRun finalRun = runCaptor.getAllValues().get(runCaptor.getAllValues().size() - 1);
        assertThat(finalRun.getStatus()).isEqualTo(BackupRun.STATUS_FAILED);
        assertThat(finalRun.getError()).contains("mysqldump exited with code 2");

        // A BACKUP_FAILED admin notification is emitted to the outbox (Req 24.2).
        assertBackupFailedEmitted("mysqldump exited with code 2");

        // Never attempted an upload since the dump failed.
        verify(storageService, never()).store(any(), any(), any(), any());
    }

    // --- Upload failure (Req 24.2) ------------------------------------------

    @Test
    void uploadFailureRecordsFailedRowAndEmitsBackupFailedNotification() {
        when(dumpRunner.dump()).thenReturn("dump".getBytes());
        when(storageService.store(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Object Storage unavailable"));

        BackupResult result = service.runBackup();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Object Storage unavailable");

        ArgumentCaptor<BackupRun> runCaptor = ArgumentCaptor.forClass(BackupRun.class);
        verify(backupRunRepository, org.mockito.Mockito.atLeastOnce()).save(runCaptor.capture());
        BackupRun finalRun = runCaptor.getAllValues().get(runCaptor.getAllValues().size() - 1);
        assertThat(finalRun.getStatus()).isEqualTo(BackupRun.STATUS_FAILED);
        assertThat(finalRun.getError()).contains("Object Storage unavailable");

        assertBackupFailedEmitted("Object Storage unavailable");
    }

    // --- Schedule interval ≤ 24h (Req 24.1) ---------------------------------

    @Test
    void defaultBackupCronFiresAtMostEvery24Hours() {
        String cron = new BackupProperties(null, null).cron(); // default 0 0 2 * * *
        assertScheduleWithin24h(cron);
    }

    @Test
    void configuredCronNeverExceeds24HourInterval() {
        // A few representative daily/twice-daily schedules that satisfy Req 24.1.
        for (String cron : List.of("0 0 2 * * *", "0 30 3 * * *", "0 0 */12 * * *", "0 0 0,12 * * *")) {
            assertScheduleWithin24h(cron);
        }
    }

    private static void assertScheduleWithin24h(String cron) {
        CronExpression expression = CronExpression.parse(cron);
        LocalDateTime first = expression.next(LocalDateTime.of(2024, 1, 1, 0, 0));
        assertThat(first).as("cron %s should fire", cron).isNotNull();
        LocalDateTime second = expression.next(first);
        assertThat(second).as("cron %s should fire again", cron).isNotNull();
        assertThat(Duration.between(first, second))
                .as("cron %s must back up at least once every 24h (Req 24.1)", cron)
                .isLessThanOrEqualTo(Duration.ofHours(24));
    }

    private void assertBackupFailedEmitted(String expectedErrorFragment) {
        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(OutboxEvent.EVENT_BACKUP_FAILED);
        assertThat(event.getAggregateType()).isEqualTo(OutboxEvent.AGGREGATE_SYSTEM);
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(String.valueOf(event.getPayload().get("error"))).contains(expectedErrorFragment);
        assertThat(event.getPayload()).containsKey("backupDate");
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }
}
