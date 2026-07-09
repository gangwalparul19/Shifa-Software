package com.shifa.oms.mail.report;

import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.mail.MailException;
import com.shifa.oms.mail.MailProperties;
import com.shifa.oms.mail.MailService;
import com.shifa.oms.mail.template.EmailModels;
import com.shifa.oms.mail.template.EmailRenderer;
import com.shifa.oms.mail.template.RenderedEmail;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds and sends the consolidated daily report email (Consolidated Daily
 * Report feature).
 *
 * <p>For a given day it loads that day's orders in the half-open window
 * {@code [day 00:00, day+1 00:00)} via
 * {@link OrderRepository#findByCreatedAtBetween}, resolves each order's
 * {@code createdBy} to a salesperson name from the {@link UserRepository},
 * projects them to {@link ReportOrder}, builds the report via the pure
 * {@link DailyReport#build} function, renders a branded HTML email via
 * {@link EmailRenderer}, and sends it to {@code app.mail.digest-to} through the
 * {@link MailService}.
 *
 * <p>The aggregation itself is a pure function (see {@link DailyReport}), so this
 * service only wires it to the database, the user directory, and the mailer. It
 * always returns a small {@link Result} summary — even when no recipient is
 * configured (in which case the email is skipped, not sent) — so the ADMIN
 * trigger endpoint can report what happened. A mail send failure is logged and
 * swallowed so it can never stall the scheduler.
 */
@Service
@Transactional(readOnly = true)
public class DailyReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final MailProperties mailProperties;
    private final EmailRenderer emailRenderer;

    public DailyReportService(OrderRepository orderRepository,
                              UserRepository userRepository,
                              MailService mailService,
                              MailProperties mailProperties,
                              EmailRenderer emailRenderer) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.mailService = mailService;
        this.mailProperties = mailProperties;
        this.emailRenderer = emailRenderer;
    }

    /**
     * Builds the consolidated report for {@code day} and sends it when a
     * recipient is configured; otherwise it is skipped (logged). Never rethrows a
     * {@link MailException}.
     *
     * @param day the day to summarise (typically yesterday)
     * @return a small summary of what was built/sent
     */
    public Result sendConsolidatedReportFor(LocalDate day) {
        List<ReportOrder> projected = loadOrdersFor(day);
        DailyReport report = DailyReport.build(day, projected);

        String to = mailProperties.digestTo();
        boolean recipientConfigured = to != null && !to.isBlank();
        if (!recipientConfigured) {
            log.info("Consolidated daily report for {} built ({} order(s)) but not sent: "
                    + "no recipient configured (app.mail.digest-to blank).",
                    day, report.overall().orderCount());
            return Result.from(report, false);
        }

        RenderedEmail rendered = emailRenderer.renderConsolidatedReport(
                new EmailModels.ConsolidatedReport(report));
        try {
            mailService.send(rendered.toMessage(to));
            log.info("Sent consolidated daily report for {} to {} ({} order(s)).",
                    day, to, report.overall().orderCount());
        } catch (MailException e) {
            log.warn("Failed to send consolidated daily report for {}: {}", day, e.getMessage());
        }
        return Result.from(report, true);
    }

    /**
     * Loads all orders created on {@code day}, projected to {@link ReportOrder}
     * with each order's salesperson name resolved from the user directory.
     */
    private List<ReportOrder> loadOrdersFor(LocalDate day) {
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay();
        List<OrderEntity> orders = orderRepository.findByCreatedAtBetween(from, to);

        Map<Long, String> names = resolveSalespersonNames(orders);

        List<ReportOrder> result = new ArrayList<>(orders.size());
        for (OrderEntity o : orders) {
            result.add(new ReportOrder(
                    o.getOrderStatus(),
                    o.getTotalAmount(),
                    o.getCodAmount(),
                    o.getAmountReceived(),
                    o.getCustomerName(),
                    o.getCustomerMobile(),
                    o.getCreatedBy(),
                    o.getCreatedBy() == null ? null : names.get(o.getCreatedBy())));
        }
        return result;
    }

    /**
     * Batch-resolves the distinct {@code createdBy} ids on the orders to a
     * display name (full name when present, else username) in a single query.
     */
    private Map<Long, String> resolveSalespersonNames(List<OrderEntity> orders) {
        Set<Long> ids = new HashSet<>();
        for (OrderEntity o : orders) {
            if (o.getCreatedBy() != null) {
                ids.add(o.getCreatedBy());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (User u : userRepository.findAllById(ids)) {
            String name = (u.getFullName() != null && !u.getFullName().isBlank())
                    ? u.getFullName() : u.getUsername();
            names.put(u.getId(), name);
        }
        return names;
    }

    /**
     * A small summary of a report run, returned to the ADMIN trigger endpoint.
     *
     * @param date                the day summarised
     * @param orderCount          qualifying (sale) orders that day
     * @param totalSales          total sales value that day
     * @param recipientConfigured whether a recipient was configured (i.e. the email was sent)
     */
    public record Result(LocalDate date, int orderCount, java.math.BigDecimal totalSales,
                         boolean recipientConfigured) {

        static Result from(DailyReport report, boolean recipientConfigured) {
            return new Result(report.day(), report.overall().orderCount(),
                    report.overall().totalSales(), recipientConfigured);
        }
    }
}
