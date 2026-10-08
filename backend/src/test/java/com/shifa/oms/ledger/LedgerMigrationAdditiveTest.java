package com.shifa.oms.ledger;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Additive-migration regression test for the General Ledger Flyway migrations (Reqs 17.1, 17.2, 17.3).
 *
 * <p>The core backward-compatibility guarantee of this feature is that the GL schema is introduced by
 * <strong>new versioned migrations that only CREATE new tables</strong> — never altering or dropping an
 * existing table/column — so the order/procurement/expense/GST/reporting modules keep working unchanged.
 *
 * <p>This is a self-contained file-content assertion: it reads {@code V54__ledger_core.sql} and
 * {@code V55__ledger_seed.sql} straight off the test classpath (falling back to the module-relative
 * path) and asserts, after stripping SQL comments so a comment mentioning "altered"/"table" cannot
 * cause a false positive:
 * <ul>
 *   <li>both GL migrations exist and are numbered {@code >= 54};</li>
 *   <li>V54 contains {@code CREATE TABLE} for every new ledger table and contains no
 *       {@code ALTER TABLE} / {@code DROP TABLE};</li>
 *   <li>V55 contains only seed {@code INSERT}s and no {@code ALTER TABLE} / {@code DROP TABLE} of any
 *       existing table.</li>
 * </ul>
 */
class LedgerMigrationAdditiveTest {

    private static final String V54_FILE = "V54__ledger_core.sql";
    private static final String V55_FILE = "V55__ledger_seed.sql";

    /** The new ledger tables V54 must create (and nothing existing may be altered). */
    private static final String[] NEW_LEDGER_TABLES = {
            "account_groups",
            "ledger_accounts",
            "financial_years",
            "opening_balances",
            "vouchers",
            "voucher_lines",
            "ledger_voucher_sequences",
            "ledger_source_postings"
    };

    // --- (a) both GL migrations exist and are numbered V54+ ---------------------------------------

    @Test
    void glMigrationsExistAndAreNumberedV54OrHigher() throws Exception {
        assertThat(migrationVersion(V54_FILE))
                .as("V54 core migration is numbered >= 54")
                .isGreaterThanOrEqualTo(54);
        assertThat(migrationVersion(V55_FILE))
                .as("V55 seed migration is numbered >= 54")
                .isGreaterThanOrEqualTo(54);

        // Both files are present and non-empty.
        assertThat(readMigration(V54_FILE)).isNotBlank();
        assertThat(readMigration(V55_FILE)).isNotBlank();
    }

    // --- (b) V54 creates only new tables (CREATE TABLE, no ALTER/DROP) ----------------------------

    @Test
    void v54CreatesOnlyNewLedgerTablesAndNeverAltersOrDrops() throws Exception {
        String sql = stripComments(readMigration(V54_FILE)).toUpperCase();

        // A CREATE TABLE exists for every new ledger table.
        for (String table : NEW_LEDGER_TABLES) {
            assertThat(sql)
                    .as("V54 creates table %s", table)
                    .contains("CREATE TABLE " + table.toUpperCase());
        }

        // It is purely additive: no existing table/column is altered or dropped.
        assertThat(sql)
                .as("V54 must not ALTER any table (additive only)")
                .doesNotContain("ALTER TABLE");
        assertThat(sql)
                .as("V54 must not DROP any table (additive only)")
                .doesNotContain("DROP TABLE");
    }

    // --- (c) V55 seeds only (INSERTs, no ALTER/DROP) ----------------------------------------------

    @Test
    void v55SeedsOnlyAndNeverAltersOrDropsExistingTables() throws Exception {
        String sql = stripComments(readMigration(V55_FILE)).toUpperCase();

        // The seed migration inserts rows...
        assertThat(sql)
                .as("V55 contains seed INSERTs")
                .contains("INSERT INTO");

        // ...and does not alter or drop anything, nor create new tables.
        assertThat(sql)
                .as("V55 must not ALTER any table (seed only)")
                .doesNotContain("ALTER TABLE");
        assertThat(sql)
                .as("V55 must not DROP any table (seed only)")
                .doesNotContain("DROP TABLE");
        assertThat(sql)
                .as("V55 is a seed migration and must not CREATE tables")
                .doesNotContain("CREATE TABLE");
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** Parse the leading numeric version from a Flyway migration file name (e.g. V54__x.sql -> 54). */
    private static int migrationVersion(String fileName) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(fileName);
        assertThat(m.find()).as("file name %s follows the Flyway Vnn__ convention", fileName).isTrue();
        return Integer.parseInt(m.group(1));
    }

    /**
     * Read a migration file robustly: prefer the test classpath ({@code db/migration/...}, where the
     * main resources are copied), falling back to the module-relative source path.
     */
    private String readMigration(String fileName) throws IOException, URISyntaxException {
        URL url = getClass().getClassLoader().getResource("db/migration/" + fileName);
        if (url != null && "file".equals(url.getProtocol())) {
            return Files.readString(Path.of(url.toURI()), StandardCharsets.UTF_8);
        }
        Path[] candidates = {
                Path.of("src", "main", "resources", "db", "migration", fileName),
                Path.of("backend", "src", "main", "resources", "db", "migration", fileName)
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Could not locate migration file " + fileName
                + " on the classpath or under src/main/resources/db/migration");
    }

    /** Remove SQL line comments ({@code -- ...}) so commentary text cannot trip content assertions. */
    private static String stripComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int idx = line.indexOf("--");
                    return idx >= 0 ? line.substring(0, idx) : line;
                })
                .collect(Collectors.joining("\n"));
    }
}
