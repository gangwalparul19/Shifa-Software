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
 *       separated by commas or semicolons; and</li>
 *   <li>the profile email of every <em>active</em> {@link Role#ADMIN} user, unless
 *       {@code app.mail.digest-include-admins} ({@code REPORT_DIGEST_INCLUDE_ADMINS})
 *       is {@code false}.</li>
 * </ol>
 * Addresses that are not syntactically valid are skipped with a warning, so one
 * typo on a profile cannot stop the report reaching everyone else.
 */
@Component
public class ReportRecipients {

    private static final Logger log = LoggerFactory.getLogger(ReportRecipients.class);

    private final MailProperties properties;
    private final UserRepository userRepository;
    private final boolean includeAdmins;

    @Autowired
    public ReportRecipients(MailProperties properties,
                            UserRepository userRepository,
                            @Value("${app.mail.digest-include-admins:true}") boolean includeAdmins) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.includeAdmins = includeAdmins;
    }

    /** The de-duplicated, validated recipient list; empty when nobody is configured. */
    public List<String> resolve() {
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        for (String address : parse(properties.digestTo())) {
            add(byLowerCase, address, "REPORT_DIGEST_TO");
        }
        if (includeAdmins && userRepository != null) {
            for (User admin : userRepository.findByRoleAndActiveTrue(Role.ADMIN)) {
                String email = admin.getEmail();
                if (email != null && !email.isBlank()) {
                    add(byLowerCase, email.trim(), "admin user '" + admin.getUsername() + "'");
                }
            }
        }
        return new ArrayList<>(byLowerCase.values());
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

    private static void add(Map<String, String> target, String address, String source) {
        if (!isValid(address)) {
            log.warn("Skipping invalid email address from {} for report emails.", source);
            return;
        }
        target.putIfAbsent(address.toLowerCase(Locale.ROOT), address);
    }
}
