package com.shifa.oms.settings;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.settings.dto.BankAccountRequest;
import com.shifa.oms.settings.dto.BankAccountResponse;
import com.shifa.oms.settings.dto.PaymentAccountOption;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Manages configured receiving and settlement accounts. */
@Service
public class BankAccountService {

    private static final java.util.regex.Pattern IFSC =
            java.util.regex.Pattern.compile("^[A-Za-z]{4}0[A-Za-z0-9]{6}$");

    private final BankAccountRepository repository;
    private final AuditService auditService;

    public BankAccountService(BankAccountRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<BankAccountResponse> listAll() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream().map(BankAccountResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentAccountOption> activePaymentAccounts() {
        return repository.findByActiveTrueOrderBySortOrderAscIdAsc()
                .stream().map(PaymentAccountOption::from).toList();
    }

    @Transactional
    public BankAccountResponse create(BankAccountRequest request) {
        BankAccount account = new BankAccount(clean(request.displayName()), request.accountType());
        apply(account, request);
        if (account.isDefaultAccount()) clearDefaults();
        BankAccount saved = repository.save(account);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS, String.valueOf(saved.getId()),
                "Created payment account " + saved.getDisplayName());
        return BankAccountResponse.from(saved);
    }

    @Transactional
    public BankAccountResponse update(Long id, BankAccountRequest request) {
        BankAccount account = find(id);
        account.setDisplayName(clean(request.displayName()));
        account.setAccountType(request.accountType());
        apply(account, request);
        if (account.isDefaultAccount()) clearDefaultsExcept(id);
        BankAccount saved = repository.save(account);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS, String.valueOf(saved.getId()),
                "Updated payment account " + saved.getDisplayName());
        return BankAccountResponse.from(saved);
    }

    @Transactional
    public BankAccountResponse deactivate(Long id) {
        BankAccount account = find(id);
        account.setActive(false);
        account.setDefaultAccount(false);
        BankAccount saved = repository.save(account);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS, String.valueOf(saved.getId()),
                "Deactivated payment account " + saved.getDisplayName());
        return BankAccountResponse.from(saved);
    }

    /** Used by order creation and Shopify settings; only active accounts are valid. */
    @Transactional(readOnly = true)
    public BankAccount requireActive(Long id) {
        if (id == null) {
            throw new ValidationException("A receiving bank account is required for this payment.");
        }
        BankAccount account = find(id);
        if (!account.isActive()) {
            throw new ValidationException("The selected receiving bank account is inactive.");
        }
        return account;
    }

    private BankAccount find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bank account " + id + " does not exist."));
    }

    private void apply(BankAccount account, BankAccountRequest request) {
        account.setBankName(clean(request.bankName()));
        account.setAccountHolderName(clean(request.accountHolderName()));
        String accountNumber = clean(request.accountNumber());
        // Full account numbers are never returned by the response DTO. A blank
        // update therefore means "keep the existing number"; creation still
        // leaves it null when no number was supplied.
        if (accountNumber != null || account.getAccountNumber() == null) {
            account.setAccountNumber(accountNumber);
        }
        String ifsc = clean(request.ifsc());
        if (ifsc != null && !IFSC.matcher(ifsc).matches()) {
            throw new ValidationException("IFSC must be in the format HDFC0001234.");
        }
        account.setIfsc(ifsc == null ? null : ifsc.toUpperCase(java.util.Locale.ROOT));
        account.setBranch(clean(request.branch()));
        account.setUpiId(clean(request.upiId()));
        account.setLedgerAccountId(request.ledgerAccountId());
        account.setActive(request.active());
        account.setDefaultAccount(request.defaultAccount());
        account.setSortOrder(Math.max(0, request.sortOrder()));
        account.setInternalNote(clean(request.internalNote()));
    }

    private void clearDefaults() {
        repository.findAll().forEach(a -> {
            if (a.isDefaultAccount()) a.setDefaultAccount(false);
        });
    }

    private void clearDefaultsExcept(Long id) {
        repository.findAll().forEach(a -> {
            if (!a.getId().equals(id) && a.isDefaultAccount()) a.setDefaultAccount(false);
        });
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
