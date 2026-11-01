package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.AdminUserResponse;
import com.shifa.oms.mail.MailProperties;
import com.shifa.oms.mail.MailService;
import com.shifa.oms.mail.ReportRecipients;
import com.shifa.oms.mail.template.EmailModels;
import com.shifa.oms.mail.template.EmailRenderer;
import com.shifa.oms.mail.template.RenderedEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Emails the temporary credential from an administrator password reset to the
 * administrator who performed it, from the application's mail account.
 *
 * <p>Design constraints:
 * <ul>
 *   <li><strong>Never persisted.</strong> The email is sent directly and not
 *       through the outbox, because outbox payloads are stored in the database
 *       and would keep the credential in plaintext.</li>
 *   <li><strong>Never logged.</strong> Only the masked recipient is logged.</li>
 *   <li><strong>Best effort.</strong> A mail failure never fails the reset; the
 *       credential is always shown on screen too. The result is empty unless a
 *       real message was handed to the SMTP server.</li>
 *   <li><strong>Only to the acting administrator.</strong> It is not sent to the
 *       user whose password was reset.</li>
 * </ul>
 */
@Component
public class PasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetNotifier.class);
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'IST'", Locale.ENGLISH);

    private final MailService mailService;
    private final MailProperties mailProperties;
    private final EmailRenderer emailRenderer;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public PasswordResetNotifier(MailService mailService,
                                 MailProperties mailProperties,
                                 EmailRenderer emailRenderer,
                                 UserRepository userRepository,
                                 CurrentUserService currentUserService) {
        this.mailService = mailService;
        this.mailProperties = mailProperties;
        this.emailRenderer = emailRenderer;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
    }

    /**
     * Sends the credential to the signed-in administrator.
     *
     * @return the masked address the email went to, or empty when nothing was
     *         sent (mail is in MOCK mode, the admin has no valid email, or the
     *         send failed)
     */
    public Optional<String> sendToCurrentAdmin(AdminUserResponse target,
                                               String credential,
                                               LocalDateTime expiresAt) {
        try {
            // MOCK only logs, so claiming "emailed" to the admin would be untrue.
            if (mailProperties.isMock()) {
                return Optional.empty();
            }
            Optional<AuthPrincipal> principal = currentUserService.currentUser();
            if (principal.isEmpty()) {
                return Optional.empty();
            }
            User admin = userRepository.findById(principal.get().userId()).orElse(null);
            if (admin == null || !ReportRecipients.isValid(admin.getEmail())) {
                log.info("Temporary credential for user '{}' not emailed: acting admin has no valid email.",
                        target.username());
                return Optional.empty();
            }
            String to = admin.getEmail().trim();
            RenderedEmail rendered = emailRenderer.renderTemporaryCredential(
                    new EmailModels.TemporaryCredential(
                            admin.getFullName(),
                            target.fullName(),
                            target.username(),
                            credential,
                            expiresAt == null ? "" : expiresAt.format(WHEN)));
            mailService.send(rendered.toMessage(to));
            String masked = mask(to);
            log.info("Temporary credential for user '{}' emailed to acting admin {}.", target.username(), masked);
            return Optional.of(masked);
        } catch (RuntimeException e) {
            // Deliberately logs the class only; the message could echo message content.
            log.warn("Temporary credential email for user '{}' failed ({}).",
                    target.username(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** {@code jane.doe@gmail.com} becomes {@code j***@gmail.com}. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
