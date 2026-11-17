package com.shifa.oms.ledger.autopost;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.ledger.Voucher;
import com.shifa.oms.ledger.VoucherRepository;
import com.shifa.oms.ledger.VoucherService;
import com.shifa.oms.order.OrderCreatedAtCorrection;
import com.shifa.oms.order.OrderCreatedAtLedgerCorrection;
import com.shifa.oms.order.OrderCreatedAtLedgerCorrectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Re-dates already-posted ORDER/PAYMENT vouchers for an ADMIN punched-date correction.
 * Posted vouchers are never edited; each is reversed in its original open period and
 * reposted with the exact persisted lines on the corrected date.
 */
@Service
public class OrderDateLedgerCorrectionService {

    private static final String SOURCE_REVERSAL = "DATE_CORR_REVERSAL";
    private static final String SOURCE_REPOST = "DATE_CORR_REPOST";

    private final VoucherRepository voucherRepository;
    private final SourcePostingLogRepository sourcePostingLogRepository;
    private final VoucherService voucherService;
    private final OrderCreatedAtLedgerCorrectionRepository correctionRepository;

    @Autowired
    public OrderDateLedgerCorrectionService(VoucherRepository voucherRepository,
                                             SourcePostingLogRepository sourcePostingLogRepository,
                                             VoucherService voucherService,
                                             OrderCreatedAtLedgerCorrectionRepository correctionRepository) {
        this.voucherRepository = voucherRepository;
        this.sourcePostingLogRepository = sourcePostingLogRepository;
        this.voucherService = voucherService;
        this.correctionRepository = correctionRepository;
    }

    /**
     * Redates the order's posted ORDER and PAYMENT vouchers, if present. Independent
     * business-date sources such as delivery, refund, and payment-correction vouchers
     * are intentionally blocked by OrderService before this method is called.
     */
    @Transactional
    public void correct(OrderCreatedAtCorrection correction, LocalDate previousDate,
                        LocalDate correctedDate) {
        if (correction == null || correction.getId() == null) {
            throw new ValidationException("The order-date correction must be saved before accounting is corrected.");
        }
        if (previousDate == null || correctedDate == null) {
            throw new ValidationException("Both accounting dates are required for the correction.");
        }
        for (String sourceType : new String[]{"ORDER", "PAYMENT"}) {
            Optional<Voucher> original = findSourceVoucher(sourceType, correction.getOrderId());
            if (original.isEmpty()) {
                continue;
            }
            Voucher voucher = original.get();
            if (voucher.getReversedByVoucherId() != null) {
                throw new ValidationException("The " + sourceType
                        + " accounting entry was already reversed and cannot be re-dated automatically.");
            }

            OrderCreatedAtLedgerCorrection link = correctionRepository.saveAndFlush(
                    new OrderCreatedAtLedgerCorrection(correction.getId(), sourceType,
                            correction.getOrderId(), voucher.getId()));
            Voucher reversal = voucherService.reverseForOrderDateCorrection(
                    voucher.getId(), previousDate, SOURCE_REVERSAL, link.getId(), correction.getReason());
            link.markReversed(reversal.getId());
            correctionRepository.saveAndFlush(link);

            Voucher replacement = voucherService.repostForOrderDateCorrection(
                    voucher.getId(), correctedDate, SOURCE_REPOST, link.getId(), correction.getReason());
            link.markCompleted(replacement.getId());
            correctionRepository.save(link);
        }
    }

    private Optional<Voucher> findSourceVoucher(String sourceType, Long sourceId) {
        Optional<Voucher> direct = voucherRepository.findBySourceTypeAndSourceId(sourceType, sourceId);
        if (direct.isPresent()) {
            return direct;
        }
        return sourcePostingLogRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .map(SourcePostingLog::getVoucherId)
                .flatMap(voucherRepository::findById);
    }
}
