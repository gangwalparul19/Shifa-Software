package com.shifa.oms.mail;

import com.shifa.oms.mail.template.EmailBrandProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the mail module: enables {@link MailProperties} binding from
 * {@code app.mail.*} and {@link EmailBrandProperties} binding from
 * {@code app.mail.brand.*}. The concrete {@link MailService} is selected by the
 * {@code app.mail.mode} property ({@link MockMailService} by default,
 * {@link SmtpMailService} when {@code SMTP}).
 */
@Configuration
@EnableConfigurationProperties({MailProperties.class, EmailBrandProperties.class})
public class MailConfig {
}
