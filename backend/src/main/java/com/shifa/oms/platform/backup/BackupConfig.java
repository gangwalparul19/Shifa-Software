package com.shifa.oms.platform.backup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the backup feature: enables {@link BackupProperties} binding from
 * {@code app.backup.*} (cron schedule and {@code mysqldump} path).
 */
@Configuration
@EnableConfigurationProperties(BackupProperties.class)
public class BackupConfig {
}
