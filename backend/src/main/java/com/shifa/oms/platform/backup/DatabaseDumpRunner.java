package com.shifa.oms.platform.backup;

/**
 * Produces a logical SQL dump of the application database (Req 24.1).
 *
 * <p>This is the injectable seam that isolates {@link BackupService} from the
 * actual {@code mysqldump} process invocation, so the orchestration (record a
 * {@code backup_runs} row, gzip, upload, notify on failure) can be unit-tested
 * with a mock runner and no real database or external process. The production
 * implementation ({@link ProcessBuilderDatabaseDumpRunner}) shells out to
 * {@code mysqldump --single-transaction}.
 */
public interface DatabaseDumpRunner {

    /**
     * Runs the dump and returns the raw (uncompressed) SQL bytes.
     *
     * @return the SQL dump content
     * @throws DatabaseDumpException if the dump process fails or produces no output
     */
    byte[] dump();

    /** Raised when the dump process fails (non-zero exit, no output, or I/O error). */
    class DatabaseDumpException extends RuntimeException {
        public DatabaseDumpException(String message) {
            super(message);
        }

        public DatabaseDumpException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
