package com.shifa.oms.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Real SMTP {@link MailService} backed by Spring's {@link JavaMailSender}
 * (Feature E3), selected when {@code app.mail.mode=SMTP}. The transport is
 * configured under {@code spring.mail.*} (host, port, credentials, STARTTLS).
 *
 * <p>When the message carries an HTML body ({@link MailMessage#hasHtml()}) it
 * sends a {@code multipart/alternative} MIME message (plain-text + HTML) so
 * every client renders the best part it supports; otherwise it falls back to a
 * plain-text {@link SimpleMailMessage}. Both paths set the From address from
 * {@link MailProperties#from()} and wrap any transport error in this module's
 * {@link MailException} so callers depend only on the mail contract.
 */
@Component
@ConditionalOnProperty(prefix = "app.mail", name = "mode", havingValue = "SMTP")
public class SmtpMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailService.class);

    private final JavaMailSender mailSender;
    private final MailProperties properties;

    public SmtpMailService(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(MailMessage message) {
        if (message.hasHtml()) {
            sendMultipart(message);
        } else {
            sendPlainText(message);
        }
    }

    private void sendMultipart(MailMessage message) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(properties.from());
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            // (text, html): sets the plain-text alternative first, then the HTML
            // part, producing a multipart/alternative message.
            helper.setText(message.body() == null ? "" : message.body(), message.htmlBody());
            mailSender.send(mime);
            log.info("SMTP HTML email sent to {} with subject '{}'", message.to(), message.subject());
        } catch (MessagingException | org.springframework.mail.MailException ex) {
            throw new MailException(
                    "Failed to send HTML email to " + message.to() + ": " + ex.getMessage(), ex);
        }
    }

    private void sendPlainText(MailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.from());
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        try {
            mailSender.send(mail);
            log.info("SMTP email sent to {} with subject '{}'", message.to(), message.subject());
        } catch (org.springframework.mail.MailException ex) {
            throw new MailException(
                    "Failed to send email to " + message.to() + ": " + ex.getMessage(), ex);
        }
    }
}
