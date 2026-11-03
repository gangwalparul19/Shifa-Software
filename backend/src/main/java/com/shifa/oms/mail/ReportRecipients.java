package com.shifa.oms.mail;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out who receives the business report emails (daily and weekly).
 *
 * <p>The list is, in order and without duplicates:
 * <ol>
 *   <li>every address in {@code app.mail.digest-to} ({@code REPORT_DIGEST_TO}),
 *       separated by commas or semicolons;</li>
 *   <li>the profile email of every <em>active</em> {@link Role#ADMIN} user, unless
 *       {@code app.mail.digest-include-admins} ({@code REPORT_DIGEST_INCLUDE_ADMINS})
 *       is {@code false}; and</li>
 *   <li>every <em>active</em> extra address an admin added under Settings → Email
 *       ({@link ReportRecipient}), which is how an admin with no profile email, or someone who is
 *       not a user at all, gets the reports.</li>
 * </ol>
 * Addresses that are not syntactically valid are skipped with a warning, so one
 * typo on a profile cannot stop the report reaching everyone else. When the same address appears
 * twice the first occurrence wins, so nobody receives a report twice.
 */
@Component
public class ReportRecipients {

    private static final Logger log = LoggerFactory.getLogger(ReportRecipients.class);

    /** Where a recipient comes from. */
    public enum Source {
        /** An address set on the server ({@code REPORT_DIGEST_TO}). */
        SERVER_SETTING,
        /** The profile email of an active admin user. */
        ADMIN_USER,
        /** An address added under Settings → Email. */
        ADDED
    }

    /**
     * One resolved recipient.
     *
     * @param refId the user id (for {@link Source#ADMIN_USER}) or the {@link ReportRecipient} id
     *              (for {@link Source#ADDED}); {@code null} for a server setting
     */
    public record Entry(String email, String name, Source source, Long refId) {
    }

    private final MailProperties properties;
    private final UserRepository userRepository;
    private final ReportRecipientRepository extras;
    private final boolean includeAdmins;

    @Autowired
    public ReportRecipients(MailProperties properties,
                            UserRepository userRepository,
                            ReportRecipientRepository extras,
                            @Value("${app.mail.digest-include-admins:true}") boolean includeAdmins) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.extras = extras;
        this.includeAdmins = includeAdmins;
    }

    /** The de-duplicated, validated addresses the scheduled report is sent to; empty when nobody is configured. */
    public List<String> resolve() {
        return resolveEntries().stream().map(Entry::email).toList();
    }

    /** Who the scheduled report goes to, with where each address comes from. */
    public List<Entry> resolveEntries() {
        return collect(includeAdmins);
    }

    /**
     * Everyone who can be chosen when sending the report by hand: all active admins with an email,
     * every active extra address and the server addresses. Unlike {@link #resolveEntries()} this
     * ignores {@code REPORT_DIGEST_INCLUDE_ADMINS}, because choosing someone by hand is a deliberate act.
     */
    public List<Entry> candidates() {
        return collect(true);
    }

    /** Whether the scheduled report includes admin users automatically. */
    public boolean isIncludingAdmins() {
        return includeAdmins;
    }

    private List<Entry> collect(boolean withAdmins) {
        Map<String, Entry> byLowerCase = new LinkedHashMap<>();
        for (String address : parse(properties.digestTo())) {
            add(byLowerCase, new Entry(address, "Server setting", Source.SERVER_SETTING, null), "REPORT_DIGEST_TO");
        }
        if (withAdmins && userRepository != null) {
            for (User admin : userRepository.findByRoleAndActiveTrue(Role.ADMIN)) {
                String email = admin.getEmail();
                if (email != null && !email.isBlank()) {
                    add(byLowerCase, new Entry(email.trim(), displayName(admin), Source.ADMIN_USER, admin.getId()),
                            "admin user '" + admin.getUsername() + "'");
                }
            }
        }
        if (extras != null) {
            for (ReportRecipient extra : extras.findByActiveTrueOrderByIdAsc()) {
                String name = extra.getLabel() == null || extra.getLabel().isBlank() ? extra.getEmail() : extra.getLabel();
                add(byLowerCase, new Entry(extra.getEmail().trim(), name, Source.ADDED, extra.getId()),
                        "added recipient #" + extra.getId());
            }
        }
        return new ArrayList<>(byLowerCase.values());
    }

    /** The name shown for an admin user: the full name, else the username. */
    public static String displayName(User user) {
        String full = user.getFullName();
        return full == null || full.isBlank() ? user.getUsername() : full.trim();
    }

    /** Splits a comma/semicolon separated list into trimmed, non-empty addresses. */
    public static List<String> parse(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String part : raw.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Whether the text is one plain address such as {@code owner@shifa.com}: valid, with no spaces,
     * no {@code "Name <address>"} form and no list separators.
     */
    public static boolean isPlainAddress(String address) {
        if (!isValid(address)) {
            return false;
        }
        String a = address.trim();
        return a.indexOf('@') == a.lastIndexOf('@')
                && a.chars().noneMatch(c -> Character.isWhitespace(c) || c == '<' || c == '>' || c == ',' || c == ';');
    }

    /** Whether the text is a single syntactically valid email address. */
    public static boolean isValid(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            new InternetAddress(address, true).validate();
            return true;
        } catch (AddressException e) {
            return false;
        }
    }

    private static void add(Map<String, Entry> target, Entry entry, String source) {
        if (!isValid(entry.email())) {
            log.warn("Skipping invalid email address from {} for report emails.", source);
            return;
        }
        target.putIfAbsent(entry.email().toLowerCase(Locale.ROOT), entry);
    }
}
