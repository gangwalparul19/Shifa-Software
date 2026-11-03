package com.shifa.oms.mail;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.mail.ReportRecipients.Entry;
import com.shifa.oms.mail.ReportRecipients.Source;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The list of people who receive the admin report emails, as managed under Settings → Email.
 *
 * <p>Shows every active admin user first (with or without an email on their profile), then the
 * extra addresses added here, then any addresses set on the server. Extra addresses are kept in
 * {@code report_recipients} and used for every report until paused or removed; admin users need no
 * entry of their own, they are read from the user table each time.
 */
@Service
public class ReportRecipientService {

    private static final int MAX_EMAIL = 255;
    private static final int MAX_LABEL = 100;

    private final ReportRecipientRepository repository;
    private final ReportRecipients recipients;
    private final UserRepository userRepository;
    private final MailProperties mailProperties;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;

    public ReportRecipientService(ReportRecipientRepository repository,
                                  ReportRecipients recipients,
                                  UserRepository userRepository,
                                  MailProperties mailProperties,
                                  AuditService auditService,
                                  CurrentUserService currentUserService) {
        this.repository = repository;
        this.recipients = recipients;
        this.userRepository = userRepository;
        this.mailProperties = mailProperties;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
    }

    /**
     * One line in the list.
     *
     * @param kind      {@code ADMIN_USER}, {@code ADDED} or {@code SERVER}
     * @param userId    the user id for an admin user
     * @param extraId   the id of an added address
     * @param email     the address, or {@code null} when an admin has none on their profile
     * @param active    false for a paused added address
     * @param scheduled whether the scheduled morning report is sent to this line
     * @param sendable  whether this line can be chosen in "Send now"
     * @param note      why a line is not used, or {@code null}
     */
    public record Row(String kind, Long userId, Long extraId, String name, String email, boolean active,
                      boolean scheduled, boolean sendable, String note) {
    }

    /**
     * @param adminsIncluded         whether the scheduled report includes admin users automatically
     * @param scheduledSendEnabled   whether the server sends the morning report by itself
     * @param scheduledCount         how many addresses the scheduled report goes to
     */
    public record Overview(List<Row> rows, boolean adminsIncluded, boolean scheduledSendEnabled, int scheduledCount) {
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        List<Entry> scheduled = recipients.resolveEntries();
        Set<String> scheduledKeys = keys(scheduled);
        Set<String> sendableKeys = keys(recipients.candidates());
        boolean adminsIncluded = recipients.isIncludingAdmins();

        List<Row> rows = new ArrayList<>();

        List<User> admins = new ArrayList<>(userRepository.findByRoleAndActiveTrue(Role.ADMIN));
        admins.sort(Comparator.comparing(User::getId));
        for (User admin : admins) {
            String email = admin.getEmail() == null || admin.getEmail().isBlank() ? null : admin.getEmail().trim();
            String key = key(Source.ADMIN_USER, admin.getId(), email);
            boolean isScheduled = email != null && scheduledKeys.contains(key);
            boolean isSendable = email != null && sendableKeys.contains(key);
            String note = null;
            if (email == null) {
                note = "No email on the profile. Add an address below to include this person.";
            } else if (!ReportRecipients.isValid(email)) {
                note = "The email on the profile is not a valid address.";
            } else if (!isSendable) {
                note = "The same address is already listed once, so it is not repeated.";
            } else if (!adminsIncluded) {
                note = "Admins are not added automatically (switched off on the server).";
            }
            rows.add(new Row("ADMIN_USER", admin.getId(), null, ReportRecipients.displayName(admin), email, true,
                    isScheduled, isSendable, note));
        }

        for (ReportRecipient extra : repository.findAllByOrderByIdAsc()) {
            String key = key(Source.ADDED, extra.getId(), extra.getEmail());
            boolean isScheduled = extra.isActive() && scheduledKeys.contains(key);
            boolean isSendable = extra.isActive() && sendableKeys.contains(key);
            String note = null;
            if (!extra.isActive()) {
                note = "Paused. It will not receive reports until resumed.";
            } else if (!isSendable) {
                note = "The same address is already listed once, so it is not repeated.";
            }
            String name = extra.getLabel() == null || extra.getLabel().isBlank() ? extra.getEmail() : extra.getLabel();
            rows.add(new Row("ADDED", null, extra.getId(), name, extra.getEmail(), extra.isActive(),
                    isScheduled, isSendable, note));
        }

        for (String address : ReportRecipients.parse(mailProperties.digestTo())) {
            String key = key(Source.SERVER_SETTING, null, address);
            boolean valid = ReportRecipients.isValid(address);
            boolean isScheduled = valid && scheduledKeys.contains(key);
            boolean isSendable = valid && sendableKeys.contains(key);
            String note = !valid ? "Not a valid address." : null;
            rows.add(new Row("SERVER", null, null, "Set on the server", address, true, isScheduled, isSendable, note));
        }

        return new Overview(rows, adminsIncluded, mailProperties.isDigestEnabled(), scheduled.size());
    }

    /** Adds an extra address that will receive every report from now on. */
    @Transactional
    public Row add(String rawEmail, String rawName) {
        String email = rawEmail == null ? "" : rawEmail.trim();
        if (email.isEmpty()) {
            throw new ValidationException("Enter an email address.");
        }
        if (email.length() > MAX_EMAIL) {
            throw new ValidationException("The email address is too long.");
        }
        if (!ReportRecipients.isPlainAddress(email)) {
            throw new ValidationException("Enter one valid email address, for example name@company.com.");
        }
        String label = rawName == null ? "" : rawName.trim();
        if (label.length() > MAX_LABEL) {
            throw new ValidationException("The name is too long (at most " + MAX_LABEL + " characters).");
        }
        for (Entry existing : recipients.candidates()) {
            if (existing.email().equalsIgnoreCase(email)) {
                throw new ValidationException("That address already receives the report (" + existing.name() + ").");
            }
        }
        if (repository.existsByEmailIgnoreCase(email)) {
            throw new ValidationException("That address is already in the list (it is paused).");
        }

        AuthPrincipal actor = currentUserService.requireCurrentUser();
        ReportRecipient saved = repository.save(
                new ReportRecipient(email, label.isEmpty() ? null : label, actor.userId(), actor.username()));
        auditService.record(AuditActions.REPORT_RECIPIENT_ADDED, AuditActions.ENTITY_REPORT_RECIPIENT,
                String.valueOf(saved.getId()), "Added " + email + " to the admin report recipients.");
        String name = saved.getLabel() == null ? saved.getEmail() : saved.getLabel();
        return new Row("ADDED", null, saved.getId(), name, saved.getEmail(), true, true, true, null);
    }

    /** Pauses or resumes an added address. */
    @Transactional
    public void setActive(Long id, boolean active) {
        ReportRecipient recipient = find(id);
        if (recipient.isActive() == active) {
            return;
        }
        recipient.setActive(active);
        repository.save(recipient);
        auditService.record(AuditActions.REPORT_RECIPIENT_UPDATED, AuditActions.ENTITY_REPORT_RECIPIENT,
                String.valueOf(id), (active ? "Resumed " : "Paused ") + recipient.getEmail()
                        + (active ? " for the admin reports." : " (no reports until resumed)."));
    }

    /** Removes an added address for good. */
    @Transactional
    public void remove(Long id) {
        ReportRecipient recipient = find(id);
        repository.delete(recipient);
        auditService.record(AuditActions.REPORT_RECIPIENT_REMOVED, AuditActions.ENTITY_REPORT_RECIPIENT,
                String.valueOf(id), "Removed " + recipient.getEmail() + " from the admin report recipients.");
    }

    private ReportRecipient find(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("That recipient no longer exists."));
    }

    private static Set<String> keys(List<Entry> entries) {
        Set<String> keys = new HashSet<>();
        for (Entry e : entries) {
            keys.add(key(e.source(), e.refId(), e.email()));
        }
        return keys;
    }

    /** Identifies one line: the user id or recipient id when there is one, else the address. */
    private static String key(Source source, Long refId, String email) {
        return source + "|" + (refId != null ? refId : (email == null ? "" : email.trim().toLowerCase(Locale.ROOT)));
    }
}
