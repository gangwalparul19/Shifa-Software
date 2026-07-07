package com.shifa.oms.mail;

import com.shifa.oms.mail.template.EmailModels;
import com.shifa.oms.mail.template.EmailRenderer;
import com.shifa.oms.mail.template.RenderedEmail;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Scheduled daily sales-digest email (Feature C4).
 *
 * <p>Once a day (default 06:30, {@code REPORT_DIGEST_CRON}) it summarises the
 * <em>previous</em> day's orders and, if a recipient is configured
 * ({@code app.mail.digest-to} non-blank), emails a plain-text digest via the
 * {@link MailService}.
 *
 * <p><strong>Why it does not reuse {@code ReportService}.</strong>
 * {@code ReportService} scopes its data to the authenticated request user via
 * {@code CurrentUserService}, which does not exist on a scheduler thread. This
 * job therefore queries the orders directly with {@code findAllScoped(null)}
 * (admin scope = all orders), the same finder {@code ReportService} uses, then
 * filters to yesterday itself.
 *
 * <p>Body assembly lives in the pure, side-effect-free
 * {@link #buildDigestBody(List, LocalDate)} so it can be unit-tested
 * deterministically without the scheduler, the DB, or Mockito.
 */
@Component
public class DailyDigestJob {

    private static final Logger log = LoggerFactory.getLogger(DailyDigestJob.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final OrderRepository orderRepository;
    private final MailService mailService;
    private final MailProperties mailProperties;
    private final EmailRenderer emailRenderer;

    public DailyDigestJob(OrderRepository orderRepository,
                          MailService mailService,
                          MailProperties mailProperties,
                          EmailRenderer emailRenderer) {
        this.orderRepository = orderRepository;
        this.mailService = mailService;
        this.mailProperties = mailProperties;
        this.emailRenderer = emailRenderer;
    }

    /**
     * Scheduled entry point: builds and (if configured) sends yesterday's sales
     * digest. Failures are logged, never rethrown, so a mail outage can never
     * stall the scheduler.
     */
    @Scheduled(cron = "${REPORT_DIGEST_CRON:0 30 6 * * *}")
    public void sendDailyDigest() {
        try {
            sendDigestFor(LocalDate.now().minusDays(1));
        } catch (RuntimeException e) {
            log.warn("Daily sales digest cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Builds the digest for the given day and sends it when a recipient is
     * configured. Package-visible so it can be driven directly in tests.
     *
     * @param day the day to summarise (typically yesterday)
     */
    @Transactional(readOnly = true)
    public void sendDigestFor(LocalDate day) {
        String to = mailProperties.digestTo();
        if (to == null || to.isBlank()) {
            log.debug("Daily sales digest skipped: no recipient configured (app.mail.digest-to blank).");
            return;
        }
        List<DigestOrder> orders = loadOrdersFor(day);
        RenderedEmail rendered = emailRenderer.renderDigest(EmailModels.Digest.from(orders, day));
        try {
            mailService.send(rendered.toMessage(to));
        } catch (MailException e) {
            log.warn("Failed to send daily sales digest for {}: {}", day, e.getMessage());
        }
    }

    /** Loads all orders created on the given day, projected to {@link DigestOrder}. */
    private List<DigestOrder> loadOrdersFor(LocalDate day) {
        List<OrderEntity> all = orderRepository.findAllScoped(null);
        List<DigestOrder> result = new ArrayList<>();
        for (OrderEntity o : all) {
            if (o.getCreatedAt() != null && day.equals(o.getCreatedAt().toLocalDate())) {
                result.add(new DigestOrder(
                        o.getOrderStatus(),
                        o.getTotalAmount(),
                        o.getCodAmount(),
                        o.getCustomerName(),
                        o.getState()));
            }
        }
        return result;
    }

    /**
     * Builds the plain-text digest body for a day — a pure function over the
     * supplied orders (Feature C4). Counts and totals exclude REJECTED /
     * CANCELLED orders, which never produced revenue. When there are no
     * qualifying orders it still returns a readable "no orders" body.
     *
     * @param orders the orders created on {@code day} (any statuses)
     * @param day    the day being summarised
     * @return the plain-text body
     */
    public String buildDigestBody(List<DigestOrder> orders, LocalDate day) {
        List<DigestOrder> sales = new ArrayList<>();
        for (DigestOrder o : orders) {
            if (o.countsAsSale()) {
                sales.add(o);
            }
        }

        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal codSales = BigDecimal.ZERO;
        BigDecimal prepaidSales = BigDecimal.ZERO;
        int codCount = 0;
        int prepaidCount = 0;
        for (DigestOrder o : sales) {
            BigDecimal amount = o.totalOrZero();
            totalSales = totalSales.add(amount);
            if (o.isCod()) {
                codSales = codSales.add(amount);
                codCount++;
            } else {
                prepaidSales = prepaidSales.add(amount);
                prepaidCount++;
            }
        }

        int excluded = orders.size() - sales.size();

        StringBuilder sb = new StringBuilder();
        sb.append("Shifa Herbal Remedies — daily sales digest\n");
        sb.append("Date: ").append(day.format(DAY)).append("\n\n");

        if (sales.isEmpty()) {
            sb.append("No orders yesterday.\n");
        } else {
            sb.append("Orders: ").append(sales.size()).append("\n");
            sb.append("Total sales: ").append(totalSales.toPlainString()).append("\n");
            sb.append("  Prepaid: ").append(prepaidCount)
                    .append(" order(s), ").append(prepaidSales.toPlainString()).append("\n");
            sb.append("  COD: ").append(codCount)
                    .append(" order(s), ").append(codSales.toPlainString()).append("\n");
        }
        if (excluded > 0) {
            sb.append("\nExcluded (rejected/cancelled): ").append(excluded).append("\n");
        }
        return sb.toString();
    }
}
