package com.shifa.oms.platform.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the scheduled database backup, bound from {@code app.backup.*}
 * (design "Daily backup strategy (Req 24)"; base config in {@code application.yml}).
 *
 * @param cron          the Spring cron expression for the nightly backup job. Its
 *                      firing interval must be at most 24h to satisfy Req 24.1;
 *                      the default {@code 0 0 2 * * *} runs daily at 02:00.
 * @param mysqldumpPath path to the {@code mysqldump} executable used to produce
 *                      the SQL dump (default {@code mysqldump}, resolved on PATH)
 */
@ConfigurationProperties(prefix = "app.backup")
public record BackupProperties(String cron, String mysqldumpPath) {

    public BackupProperties {
        if (cron == null || cron.isBlank()) {
            cron = "0 0 2 * * *";
        }
        if (mysqldumpPath == null || mysqldumpPath.isBlank()) {
            mysqldumpPath = "mysqldump";
        }
    }
}
