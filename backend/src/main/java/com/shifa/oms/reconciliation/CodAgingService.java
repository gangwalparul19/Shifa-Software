package com.shifa.oms.reconciliation;

import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.dto.CodAgingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes COD aging buckets for the accountant/CA (cod-aging enhancement):
 * groups unsettled {@link ReceivableType#COD_RECEIVABLE} rows by how long they
 * have been outstanding and flags money owed beyond the courier payout SLA, so
 * the accountant can see at a glance what to chase and how overdue it is.
 *
 * <p>Pure read-only aggregation over the {@code receivables} ledger; age is the
 * number of whole days between each receivable's {@code created_at} and today
 * (an injected {@link Clock}, so tests are deterministic).
 */
@Service
public class CodAgingService {

    /** Bucket upper bounds (inclusive, days); the last bucket is open-ended. */
    private static final int[] BUCKET_MAX = {7, 15, 30};
    private static final String[] BUCKET_LABELS = {"0–7 days", "8–15 days", "16–30 days", "30+ days"};

    private final ReceivableRepository receivableRepository;
    private final int slaDays;
    private final Clock clock;

    @Autowired
    public CodAgingService(ReceivableRepository receivableRepository,
                           @Value("${app.reconciliation.cod-sla-days:14}") int slaDays) {
        this(receivableRepository, slaDays, Clock.systemDefaultZone());
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    CodAgingService(ReceivableRepository receivableRepository, int slaDays, Clock clock) {
        this.receivableRepository = receivableRepository;
        this.slaDays = slaDays;
        this.clock = clock;
    }

    /** Builds the COD aging summary from the current unsettled COD receivables. */
    @Transactional(readOnly = true)
    public CodAgingResponse aging() {
        LocalDate today = LocalDate.now(clock);
        List<ReceivableEntity> unsettled = receivableRepository
                .findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(ReceivableType.COD_RECEIVABLE);

        int[] counts = new int[BUCKET_LABELS.length];
        BigDecimal[] amounts = new BigDecimal[BUCKET_LABELS.length];
        for (int i = 0; i < amounts.length; i++) {
            amounts[i] = BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        int overSlaCount = 0;
        BigDecimal overSlaAmount = BigDecimal.ZERO;

        for (ReceivableEntity r : unsettled) {
            BigDecimal amount = nz(r.getAmount());
            long ageDays = ageInDays(r, today);
            int bucket = bucketFor(ageDays);
            counts[bucket]++;
            amounts[bucket] = amounts[bucket].add(amount);
            total = total.add(amount);
            if (ageDays > slaDays) {
                overSlaCount++;
                overSlaAmount = overSlaAmount.add(amount);
            }
        }

        List<CodAgingResponse.Bucket> buckets = new ArrayList<>(BUCKET_LABELS.length);
        buckets.add(new CodAgingResponse.Bucket(BUCKET_LABELS[0], 0, 7, counts[0], amounts[0]));
        buckets.add(new CodAgingResponse.Bucket(BUCKET_LABELS[1], 8, 15, counts[1], amounts[1]));
        buckets.add(new CodAgingResponse.Bucket(BUCKET_LABELS[2], 16, 30, counts[2], amounts[2]));
        buckets.add(new CodAgingResponse.Bucket(BUCKET_LABELS[3], 31, null, counts[3], amounts[3]));

        return new CodAgingResponse(buckets, total, slaDays, overSlaCount, overSlaAmount);
    }

    /** Whole days between the receivable's creation and today (0 when unknown). */
    private static long ageInDays(ReceivableEntity r, LocalDate today) {
        if (r.getCreatedAt() == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(r.getCreatedAt().toLocalDate(), today);
        return Math.max(0, days);
    }

    /** The bucket index for an age in days (0..3). */
    private static int bucketFor(long ageDays) {
        for (int i = 0; i < BUCKET_MAX.length; i++) {
            if (ageDays <= BUCKET_MAX[i]) {
                return i;
            }
        }
        return BUCKET_MAX.length; // the open-ended 30+ bucket
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
