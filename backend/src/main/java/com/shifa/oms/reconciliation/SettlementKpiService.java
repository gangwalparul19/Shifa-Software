package com.shifa.oms.reconciliation;

import com.shifa.oms.order.OrderPaymentRepository;
import com.shifa.oms.settings.BankAccount;
import com.shifa.oms.settings.BankAccountRepository;
import com.shifa.oms.reconciliation.dto.SettlementKpiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Account-wise reconciliation of upfront order payments against imported bank
 * statements. QuikShip COD and Shopify payout rows are intentionally excluded.
 */
@Service
public class SettlementKpiService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");
    private final OrderPaymentRepository paymentRepository;
    private final SettlementLineRepository lineRepository;
    private final BankAccountRepository bankAccountRepository;
    private final Clock clock;

    @Autowired
    public SettlementKpiService(OrderPaymentRepository paymentRepository,
                                SettlementLineRepository lineRepository,
                                BankAccountRepository bankAccountRepository) {
        this(paymentRepository, lineRepository, bankAccountRepository, Clock.system(BUSINESS_ZONE));
    }

    SettlementKpiService(OrderPaymentRepository paymentRepository,
                         SettlementLineRepository lineRepository,
                         BankAccountRepository bankAccountRepository,
                         Clock clock) {
        this.paymentRepository = paymentRepository;
        this.lineRepository = lineRepository;
        this.bankAccountRepository = bankAccountRepository;
        this.clock = clock;
    }

    /** Defaults both dates to today in IST. A missing bound means all time. */
    @Transactional(readOnly = true)
    public SettlementKpiResponse kpis(LocalDate requestedFrom, LocalDate requestedTo, boolean allTime) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = requestedFrom;
        LocalDate to = requestedTo;
        if (allTime) {
            from = null;
            to = null;
        } else if (from == null && to == null) {
            from = today;
            to = today;
        } else if (from == null) {
            from = to;
        } else if (to == null) {
            to = from;
        }
        if (to != null && from != null && to.isBefore(from)) {
            throw new com.shifa.oms.common.ValidationException("KPI end date cannot be before the start date.");
        }

        LocalDateTime fromDateTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toDateTime = to == null ? null : to.plusDays(1).atStartOfDay();
        Map<Long, AccountTotals> totals = new LinkedHashMap<>();

        for (BankAccount account : bankAccountRepository.findAllByOrderBySortOrderAscIdAsc()) {
            totals.put(account.getId(), new AccountTotals(account.getId(), account.getDisplayName()));
        }
        if (fromDateTime == null) {
            for (OrderPaymentRepository.UpfrontPaymentKpiRow row : paymentRepository.upfrontPaymentKpisAll()) {
                totals.computeIfAbsent(row.getAccountId(), id -> new AccountTotals(id, row.getAccountName()))
                        .addPayment(row);
            }
        } else {
            for (OrderPaymentRepository.UpfrontPaymentKpiRow row :
                    paymentRepository.upfrontPaymentKpisBetween(fromDateTime, toDateTime)) {
                totals.computeIfAbsent(row.getAccountId(), id -> new AccountTotals(id, row.getAccountName()))
                        .addPayment(row);
            }
        }

        if (from == null) {
            for (SettlementLineRepository.BankReceiptKpiRow row : lineRepository.bankReceiptKpisAll()) {
                totals.computeIfAbsent(row.getAccountId(), id -> new AccountTotals(id, row.getAccountName()))
                        .addStatement(row);
            }
        } else {
            for (SettlementLineRepository.BankReceiptKpiRow row :
                    lineRepository.bankReceiptKpisBetween(from, to.plusDays(1))) {
                totals.computeIfAbsent(row.getAccountId(), id -> new AccountTotals(id, row.getAccountName()))
                        .addStatement(row);
            }
        }

        List<SettlementKpiResponse.AccountSettlementKpi> accounts = totals.values().stream()
                .sorted(Comparator.comparing(AccountTotals::accountName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(AccountTotals::toResponse)
                .toList();
        AccountTotals grand = new AccountTotals(null, "Total");
        totals.values().forEach(grand::add);
        SettlementKpiResponse.AccountSettlementKpi ignored = grand.toResponse();
        return new SettlementKpiResponse(
                from,
                to,
                grand.omsBooked,
                grand.statement,
                grand.matched,
                grand.unmatched,
                grand.statement.subtract(grand.omsBooked).setScale(2, RoundingMode.HALF_UP),
                grand.verified,
                grand.pending,
                grand.rejected,
                grand.unreviewed,
                accounts);
    }

    private static final class AccountTotals {
        private final Long accountId;
        private final String accountName;
        private BigDecimal omsBooked = BigDecimal.ZERO;
        private BigDecimal verified = BigDecimal.ZERO;
        private BigDecimal pending = BigDecimal.ZERO;
        private BigDecimal rejected = BigDecimal.ZERO;
        private BigDecimal unreviewed = BigDecimal.ZERO;
        private BigDecimal statement = BigDecimal.ZERO;
        private BigDecimal matched = BigDecimal.ZERO;
        private BigDecimal unmatched = BigDecimal.ZERO;
        private long statementRows;
        private long matchedRows;
        private long unmatchedRows;

        private AccountTotals(Long accountId, String accountName) {
            this.accountId = accountId;
            this.accountName = accountName;
        }

        private void addPayment(OrderPaymentRepository.UpfrontPaymentKpiRow row) {
            omsBooked = add(omsBooked, row.getOmsBookedAmount());
            verified = add(verified, row.getVerifiedAmount());
            pending = add(pending, row.getPendingAmount());
            rejected = add(rejected, row.getRejectedAmount());
            unreviewed = add(unreviewed, row.getUnreviewedAmount());
        }

        private void addStatement(SettlementLineRepository.BankReceiptKpiRow row) {
            statement = add(statement, row.getStatementAmount());
            matched = add(matched, row.getMatchedAmount());
            unmatched = add(unmatched, row.getUnmatchedAmount());
            statementRows += number(row.getStatementRows());
            matchedRows += number(row.getMatchedRows());
            unmatchedRows += number(row.getUnmatchedRows());
        }

        private void add(AccountTotals other) {
            omsBooked = add(omsBooked, other.omsBooked);
            verified = add(verified, other.verified);
            pending = add(pending, other.pending);
            rejected = add(rejected, other.rejected);
            unreviewed = add(unreviewed, other.unreviewed);
            statement = add(statement, other.statement);
            matched = add(matched, other.matched);
            unmatched = add(unmatched, other.unmatched);
            statementRows += other.statementRows;
            matchedRows += other.matchedRows;
            unmatchedRows += other.unmatchedRows;
        }

        private SettlementKpiResponse.AccountSettlementKpi toResponse() {
            return new SettlementKpiResponse.AccountSettlementKpi(
                    accountId, accountName, scale(omsBooked), scale(verified), scale(pending),
                    scale(rejected), scale(unreviewed), scale(statement), scale(matched),
                    scale(unmatched), scale(statement.subtract(omsBooked)), statementRows,
                    matchedRows, unmatchedRows);
        }

        private static BigDecimal add(BigDecimal left, BigDecimal right) {
            return left.add(right == null ? BigDecimal.ZERO : right);
        }

        private static long number(Number value) {
            return value == null ? 0 : value.longValue();
        }

        private static BigDecimal scale(BigDecimal value) {
            return value.setScale(2, RoundingMode.HALF_UP);
        }

        private String accountName() {
            return accountName;
        }
    }
}
