package com.shifa.oms.mail;

/**
 * A fully-resolved email ready to send (Feature E3), optionally carrying a rich
 * HTML body alongside the plain-text fallback.
 *
 * <p>Kept deliberately minimal — a single recipient, a subject line, a
 * plain-text body, and an optional {@code htmlBody} — so it can be constructed
 * in tests and logged by the mock backend without any external state.
 *
 * <p><strong>Backward compatibility.</strong> The original three-argument
 * constructor {@code new MailMessage(to, subject, body)} still works (it is kept
 * as a secondary constructor delegating with a {@code null} HTML body), so every
 * existing caller compiles and behaves unchanged. New callers should prefer the
 * {@link #text(String, String, String)} / {@link #html(String, String, String, String)}
 * factories for clarity.
 *
 * <p>When {@link #htmlBody()} is non-null the {@link SmtpMailService} sends a
 * {@code multipart/alternative} message (plain text + HTML); {@code body} is
 * always the plain-text alternative / fallback.
 *
 * @param to       the recipient email address (may itself be a comma-separated list)
 * @param subject  the subject line
 * @param body     the plain-text body (also the multipart fallback)
 * @param htmlBody the optional HTML body; {@code null} for a plain-text-only message
 */
public record MailMessage(String to, String subject, String body, String htmlBody) {

    /**
     * Backward-compatible plain-text constructor (no HTML body). Preserves the
     * original {@code new MailMessage(to, subject, body)} call sites.
     */
    public MailMessage(String to, String subject, String body) {
        this(to, subject, body, null);
    }

    /** A plain-text-only message. */
    public static MailMessage text(String to, String subject, String body) {
        return new MailMessage(to, subject, body, null);
    }

    /**
     * An HTML message with a plain-text fallback. Both parts are carried so the
     * SMTP backend can send a {@code multipart/alternative} message and clients
     * that cannot render HTML still show readable text.
     *
     * @param to           the recipient
     * @param subject      the subject line
     * @param htmlBody     the rich HTML body
     * @param textFallback the plain-text alternative
     */
    public static MailMessage html(String to, String subject, String htmlBody, String textFallback) {
        return new MailMessage(to, subject, textFallback, htmlBody);
    }

    /** Whether this message carries a (non-blank) HTML body. */
    public boolean hasHtml() {
        return htmlBody != null && !htmlBody.isBlank();
    }
}
