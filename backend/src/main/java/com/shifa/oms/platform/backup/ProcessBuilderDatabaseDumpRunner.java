package com.shifa.oms.platform.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link DatabaseDumpRunner} that shells out to {@code mysqldump}
 * (design "Daily backup strategy": {@code mysqldump --single-transaction})
 * (Req 24.1).
 *
 * <p>The MySQL host, port, and database name are parsed from the configured
 * {@code spring.datasource.url}; the credentials come from
 * {@code spring.datasource.username}/{@code password}. The command is built as an
 * explicit argument list (never a shell string) so untrusted config values
 * cannot inject additional commands, and the password is passed via the
 * {@code MYSQL_PWD} environment variable rather than the argument list so it does
 * not appear in the process table.
 *
 * <p>This component performs real process I/O and is intentionally <em>not</em>
 * exercised by unit tests; {@link BackupService} depends on the
 * {@link DatabaseDumpRunner} interface so it can be tested with a mock.
 */
@Component
public class ProcessBuilderDatabaseDumpRunner implements DatabaseDumpRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessBuilderDatabaseDumpRunner.class);

    /** jdbc:mysql://host[:port]/dbname[?params] */
    private static final Pattern JDBC_MYSQL =
            Pattern.compile("jdbc:mysql://([^:/]+)(?::(\\d+))?/([^?;]+).*");

    private static final long PROCESS_TIMEOUT_SECONDS = 300;

    /** Grace for the pipe readers to finish once the process has exited (they hit EOF at once). */
    private static final long PIPE_DRAIN_SECONDS = 30;

    private final BackupProperties properties;
    private final String datasourceUrl;
    private final String username;
    private final String password;

    public ProcessBuilderDatabaseDumpRunner(
            BackupProperties properties,
            @Value("${spring.datasource.url}") String datasourceUrl,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password) {
        this.properties = properties;
        this.datasourceUrl = datasourceUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public byte[] dump() {
        Matcher matcher = JDBC_MYSQL.matcher(datasourceUrl == null ? "" : datasourceUrl);
        if (!matcher.matches()) {
            throw new DatabaseDumpException(
                    "Unsupported or unparseable datasource URL for backup: " + datasourceUrl);
        }
        String host = matcher.group(1);
        String port = matcher.group(2) != null ? matcher.group(2) : "3306";
        String database = matcher.group(3);

        List<String> command = new ArrayList<>();
        command.add(properties.mysqldumpPath());
        command.add("--single-transaction");
        command.add("--host=" + host);
        command.add("--port=" + port);
        if (username != null && !username.isBlank()) {
            command.add("--user=" + username);
        }
        command.add(database);

        ProcessBuilder builder = new ProcessBuilder(command);
        // Pass the password out-of-band so it never appears in the argument list.
        if (password != null && !password.isBlank()) {
            builder.environment().put("MYSQL_PWD", password);
        }
        builder.redirectErrorStream(false);

        // Both pipes are drained on their own threads while this thread waits on the process.
        // Reading stdout to EOF first (as this method once did) meant the timeout below could
        // never fire if mysqldump hung, and a dump that wrote a lot to stderr would block on a
        // full stderr pipe while we were still waiting for stdout: a deadlock.
        ExecutorService pipes = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mysqldump-pipe");
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        try {
            process = builder.start();
            InputStream stdoutStream = process.getInputStream();
            InputStream stderrStream = process.getErrorStream();
            Future<byte[]> stdout = pipes.submit(() -> readAll(stdoutStream));
            Future<byte[]> stderr = pipes.submit(() -> readAll(stderrStream));

            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new DatabaseDumpException(
                        "mysqldump timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
            }
            // The process has exited, so both pipes reach EOF promptly.
            byte[] output = stdout.get(PIPE_DRAIN_SECONDS, TimeUnit.SECONDS);
            String errorText = new String(stderr.get(PIPE_DRAIN_SECONDS, TimeUnit.SECONDS));
            int exit = process.exitValue();
            if (exit != 0) {
                throw new DatabaseDumpException(
                        "mysqldump exited with code " + exit + ": " + errorText.trim());
            }
            if (output.length == 0) {
                throw new DatabaseDumpException("mysqldump produced no output");
            }
            log.debug("mysqldump produced {} bytes for database {}", output.length, database);
            return output;
        } catch (IOException e) {
            throw new DatabaseDumpException("Failed to run mysqldump: " + e.getMessage(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new DatabaseDumpException("Failed to read mysqldump output: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseDumpException("mysqldump was interrupted", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            pipes.shutdownNow();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
