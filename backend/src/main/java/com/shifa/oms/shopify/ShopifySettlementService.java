package com.shifa.oms.shopify;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.OrderPaymentRepository;
import com.shifa.oms.settings.BankAccountType;
import com.shifa.oms.settings.PaymentMethodService;
import com.shifa.oms.settings.PaymentMethodService.LinkedPaymentTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Keeps the money a customer pays on Shopify booked under the Shopify settlement account.
 *
 * <p>Shopify collects those payments itself and pays them out to the business later, so the
 * receiving account for every prepaid Shopify amount is the Shopify settlement account, not a
 * bank/QR account a salesperson picks. New imports are stamped by
 * {@link ShopifyOrderImportService}; this service resolves the account they are stamped with and
 * assigns payments imported before the account existed.
 */
@Service
public class ShopifySettlementService {

    private final PaymentMethodService paymentMethods;
    private final OrderPaymentRepository payments;

    public ShopifySettlementService(PaymentMethodService paymentMethods, OrderPaymentRepository payments) {
        this.paymentMethods = paymentMethods;
        this.payments = payments;
    }

    /** Where Shopify payments are booked right now. Empty when no active account is set up. */
    @Transactional(readOnly = true)
    public Optional<LinkedPaymentTarget> target() {
        return paymentMethods.linkedTarget(BankAccountType.SHOPIFY_SETTLEMENT);
    }

    /** The account Shopify payments go to, and how many existing payments still need assigning. */
    public record Status(boolean configured, Long accountId, String accountName, String accountMasked,
                         long unassignedPayments, BigDecimal unassignedAmount, long assignedPayments) {
    }

    @Transactional(readOnly = true)
    public Status status() {
        OrderPaymentRepository.ShopifyPaymentTotals totals = payments.shopifyPaymentTotals();
        BigDecimal unassignedAmount = totals.getUnassignedAmount() == null
                ? BigDecimal.ZERO : totals.getUnassignedAmount();
        Optional<LinkedPaymentTarget> target = target();
        return new Status(
                target.isPresent(),
                target.map(LinkedPaymentTarget::accountId).orElse(null),
                target.map(LinkedPaymentTarget::accountName).orElse(null),
                target.map(LinkedPaymentTarget::accountMasked).orElse(null),
                totals.getUnassignedCount(),
                unassignedAmount,
                totals.getAssignedCount());
    }

    /** Outcome of assigning existing payments. */
    public record AssignResult(int assigned, String accountName) {
    }

    /**
     * Books every Shopify payment that has no account under the Shopify settlement account.
     * Idempotent: payments that already have an account are not touched.
     *
     * @throws ValidationException when no active Shopify settlement account is set up
     */
    @Transactional
    public AssignResult assignExistingPayments() {
        LinkedPaymentTarget target = target().orElseThrow(() -> new ValidationException(
                "No active Shopify settlement account is set up. Add a bank account with the "
                        + "\"Shopify settlement\" purpose in Settings first."));
        int updated = payments.assignShopifyPaymentsToAccount(
                target.accountId(), target.accountName(), target.accountMasked(), target.methodCode());
        return new AssignResult(updated, target.accountName());
    }
}
