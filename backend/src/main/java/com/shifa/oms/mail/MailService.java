package com.shifa.oms.mail;

/**
 * Abstraction over outbound email (Feature E3), mirroring the swappable,
 * MODE-based design of the WhatsApp notification client.
 *
 * <p>The default backend is {@link MockMailService} (records/logs what it
 * "sent" and can simulate a failure). {@link SmtpMailService} sends real email
 * via Spring's {@code JavaMailSender} and is selected via {@code app.mail.mode}.
 *
 * <p>Sends are side-effecting and may fail; callers should treat a
 * {@link MailException} as non-fatal (e.g. the daily digest job logs and moves
 * on rather than crashing the scheduler thread).
 */
public interface MailService {

    /**
     * Sends a resolved plain-text message.
     *
     * @param message the fully-resolved message
     * @throws MailException on a send error
     */
    void send(MailMessage message);
}
