/**
 * Outbound email infrastructure (Feature E3) and the scheduled daily sales
 * digest (Feature C4).
 *
 * <p>Mirrors the swappable, MODE-based design of the WhatsApp notification
 * module: a {@link com.shifa.oms.mail.MailService} contract with a log-only
 * {@link com.shifa.oms.mail.MockMailService} (default) and a real
 * {@link com.shifa.oms.mail.SmtpMailService}, selected by {@code app.mail.mode}.
 */
package com.shifa.oms.mail;
