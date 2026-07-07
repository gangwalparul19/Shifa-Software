package com.shifa.oms.mail.template;

import com.shifa.oms.mail.MailMessage;

/**
 * The output of an {@link EmailRenderer} method: a subject line plus both the
 * rendered HTML body and its plain-text fallback (Part 2).
 *
 * <p>Carrying both renderings lets the caller build a {@code multipart/alternative}
 * {@link MailMessage} (HTML + text) in one step via {@link #toMessage(String)},
 * or eyeball just the HTML in the admin preview endpoint.
 *
 * @param subject the subject line
 * @param html    the rendered, inline-styled HTML body
 * @param text    the concise plain-text fallback (links spelled out)
 */
public record RenderedEmail(String subject, String html, String text) {

    /** Builds a multipart (HTML + text) {@link MailMessage} addressed to {@code to}. */
    public MailMessage toMessage(String to) {
        return MailMessage.html(to, subject, html, text);
    }
}
