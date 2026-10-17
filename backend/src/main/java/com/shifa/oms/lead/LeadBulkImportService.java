package com.shifa.oms.lead;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.SpreadsheetParser;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.dto.LeadImportResult;
import com.shifa.oms.order.LeadSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bulk lead upload from a CSV / Excel file (CRM expansion, Req 2). Parses the
 * sheet with the shared {@link SpreadsheetParser} (the same reader the courier
 * remittance import uses), maps columns case-insensitively by alias, validates
 * each row, and creates one lead per valid row — valid rows are created even
 * when others are invalid (Req 2.2), returning a per-row
 * {@link LeadImportResult}.
 *
 * <p>Imported leads are marked {@code created_by_admin = true} (Req 3.1) and
 * assigned to the resolved "assignee" column (username or id) or, when absent,
 * to the acting admin (Req 2.3). The upload is capped at
 * {@code app.crm.lead-import.max-rows} (default 2000) — an oversized file is
 * rejected with a clear error (Req 2.4).
 *
 * <p>Supported header aliases (case-insensitive): name / customer name (required),
 * mobile / phone (10 digits when present), email, source / lead source (resolved
 * against {@link LeadSource}; blank &rarr; OTHER; a given-but-invalid value skips
 * the row), note, assignee / assign to (username or id), tags (comma-separated
 * tag names matched against the active {@code lead_tags}; unknown names ignored),
 * followupdate / follow up date (ISO {@code yyyy-MM-dd}).
 */
@Service
public class LeadBulkImportService {

    private static final Pattern MOBILE = Pattern.compile("\\d{10}");

    private final LeadRepository leadRepository;
    private final LeadTagRepository tagRepository;
    private final LeadTagAssignmentRepository tagAssignmentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final int maxRows;

    public LeadBulkImportService(LeadRepository leadRepository,
                                 LeadTagRepository tagRepository,
                                 LeadTagAssignmentRepository tagAssignmentRepository,
                                 UserRepository userRepository,
                                 AuditService auditService,
                                 @Value("${app.crm.lead-import.max-rows:2000}") int maxRows) {
        this.leadRepository = Objects.requireNonNull(leadRepository, "leadRepository");
        this.tagRepository = Objects.requireNonNull(tagRepository, "tagRepository");
        this.tagAssignmentRepository =
                Objects.requireNonNull(tagAssignmentRepository, "tagAssignmentRepository");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
        this.maxRows = maxRows;
    }

    /**
     * Parses + imports the uploaded file, creating a lead per valid data row.
     * The first row is the header. Rejects an empty file or one exceeding the
     * row cap with a {@link ValidationException} (400).
     */
    @Transactional
    public LeadImportResult importFile(byte[] bytes, String filename, AuthPrincipal actor) {
        Objects.requireNonNull(actor, "actor");
        List<List<String>> rows;
        try {
            rows = SpreadsheetParser.parse(bytes, filename);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded file.", e);
        }
        if (rows.isEmpty()) {
            throw new ValidationException("The uploaded file is empty.");
        }
        List<String> header = rows.get(0);
        int dataRows = rows.size() - 1;
        if (dataRows > maxRows) {
            throw new ValidationException(
                    "The upload has " + dataRows + " rows, exceeding the maximum of " + maxRows + ".");
        }
        ColumnIndex cols = ColumnIndex.from(header);
        if (cols.name < 0) {
            throw new ValidationException("The file must have a \"name\" column.");
        }

        // Active tag vocabulary, by lower-cased name, for the "tags" column.
        Map<String, LeadTag> tagsByName = new HashMap<>();
        for (LeadTag tag : tagRepository.findByActiveTrueOrderBySortOrderAscIdAsc()) {
            tagsByName.put(tag.getName().toLowerCase(Locale.ROOT), tag);
        }

        LeadImportResult.Builder result = new LeadImportResult.Builder();
        for (int i = 1; i < rows.size(); i++) {
            int rowNumber = i + 1; // 1-based, as it appears in the file.
            try {
                importRow(rows.get(i), cols, tagsByName, actor);
                result.created();
            } catch (RowException e) {
                result.skipped(rowNumber, e.getMessage());
            }
        }

        LeadImportResult built = result.build();
        auditService.record(AuditActions.LEAD_IMPORTED, AuditActions.ENTITY_LEAD, null,
                "Bulk lead import by " + actor.username() + ": created " + built.created()
                        + ", skipped " + built.skipped().size());
        return built;
    }

    private void importRow(List<String> row, ColumnIndex cols, Map<String, LeadTag> tagsByName,
                           AuthPrincipal actor) {
        String name = cell(row, cols.name);
        if (name == null) {
            throw new RowException("name is required.");
        }

        String mobile = cell(row, cols.mobile);
        if (mobile != null && !MOBILE.matcher(mobile).matches()) {
            throw new RowException("mobile must be exactly 10 digits.");
        }

        LeadSource source = resolveSource(cell(row, cols.source));
        LocalDate followUpDate = resolveFollowUpDate(cell(row, cols.followUpDate));
        Long assignee = resolveAssignee(cell(row, cols.assignee), actor);

        LeadEntity lead = new LeadEntity(name, source, assignee);
        lead.setCustomerMobile(mobile);
        lead.setCustomerEmail(cell(row, cols.email));
        lead.setNote(cell(row, cols.note));
        lead.setFollowUpDate(followUpDate);
        lead.setCreatedByUserId(actor.userId());
        lead.setCreatedByAdmin(true); // every bulk-imported lead is admin-sourced (Req 3.1).
        lead.addStatusHistory(new LeadStatusHistory(null, LeadStatus.INITIAL, actor.username()));
        LeadEntity saved = leadRepository.save(lead);

        attachTags(saved.getId(), cell(row, cols.tags), tagsByName, actor);
    }

    /** Resolves the source column: blank → OTHER; a given-but-invalid value skips the row. */
    private static LeadSource resolveSource(String raw) {
        if (raw == null) {
            return LeadSource.OTHER;
        }
        try {
            return LeadSource.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RowException("source \"" + raw + "\" is not a recognised lead source.");
        }
    }

    private static LocalDate resolveFollowUpDate(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            throw new RowException("followUpDate \"" + raw + "\" is not a valid ISO date (yyyy-MM-dd).");
        }
    }

    /** Resolves the assignee by id or username; defaults to the acting admin when absent. */
    private Long resolveAssignee(String raw, AuthPrincipal actor) {
        if (raw == null) {
            return actor.userId();
        }
        // Numeric → treat as a user id.
        if (raw.chars().allMatch(Character::isDigit)) {
            Long id = Long.valueOf(raw);
            if (userRepository.findById(id).isEmpty()) {
                throw new RowException("assignee id " + id + " does not exist.");
            }
            return id;
        }
        Optional<User> byName = userRepository.findByUsername(raw);
        if (byName.isEmpty()) {
            throw new RowException("assignee \"" + raw + "\" is not a known user.");
        }
        return byName.get().getId();
    }

    /** Attaches each matching active tag (by name); unknown names are ignored (Req 2 tags). */
    private void attachTags(Long leadId, String raw, Map<String, LeadTag> tagsByName,
                            AuthPrincipal actor) {
        if (raw == null) {
            return;
        }
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            LeadTag tag = tagsByName.get(name.toLowerCase(Locale.ROOT));
            if (tag == null) {
                continue; // unknown tag name — ignore (Req 2).
            }
            if (!tagAssignmentRepository.existsByLeadIdAndTagId(leadId, tag.getId())) {
                tagAssignmentRepository.save(new LeadTagAssignment(leadId, tag.getId(), actor.userId()));
            }
        }
    }

    private static String cell(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return null;
        }
        String value = row.get(index);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Resolved 0-based column indexes for the recognised aliases (-1 when absent). */
    private record ColumnIndex(int name, int mobile, int email, int source, int note,
                               int assignee, int tags, int followUpDate) {

        static ColumnIndex from(List<String> header) {
            int name = -1, mobile = -1, email = -1, source = -1, note = -1,
                    assignee = -1, tags = -1, followUp = -1;
            for (int i = 0; i < header.size(); i++) {
                String key = header.get(i) == null ? "" : header.get(i).trim().toLowerCase(Locale.ROOT);
                switch (key) {
                    case "name", "customer name", "customername" -> name = i;
                    case "mobile", "phone", "customer mobile" -> mobile = i;
                    case "email", "customer email" -> email = i;
                    case "source", "lead source", "leadsource" -> source = i;
                    case "note", "notes" -> note = i;
                    case "assignee", "assign to", "assignto", "owner" -> assignee = i;
                    case "tags", "tag" -> tags = i;
                    case "followupdate", "follow up date", "follow-up date", "followup" -> followUp = i;
                    default -> { /* ignore unrecognised columns */ }
                }
            }
            return new ColumnIndex(name, mobile, email, source, note, assignee, tags, followUp);
        }
    }

    /** Per-row validation failure (skips just that row with the reason). */
    private static final class RowException extends RuntimeException {
        RowException(String message) {
            super(message);
        }
    }
}
