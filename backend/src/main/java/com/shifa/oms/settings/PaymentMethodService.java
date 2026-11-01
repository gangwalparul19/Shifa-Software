package com.shifa.oms.settings;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.settings.dto.PaymentMethodOption;
import com.shifa.oms.settings.dto.PaymentMethodRequest;
import com.shifa.oms.settings.dto.PaymentMethodResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the payment methods offered at order entry and resolves the method (and its linked
 * receiving account) when an order is placed.
 *
 * <p>Invariants kept here, not in the database (MySQL has no partial unique index):
 * <ul>
 *   <li>at most one method is the default, and the default is always active;</li>
 *   <li>a method that has payments recorded against it is deactivated, never deleted;</li>
 *   <li>a method that auto-selects an account always requires an account.</li>
 * </ul>
 */
@Service
public class PaymentMethodService {

    private static final int CODE_MAX = 30;

    private final PaymentMethodRepository repository;
    private final BankAccountRepository bankAccountRepository;
    private final AppSettingsRepository settingsRepository;
    private final AuditService auditService;

    public PaymentMethodService(PaymentMethodRepository repository,
                                BankAccountRepository bankAccountRepository,
                                AppSettingsRepository settingsRepository,
                                AuditService auditService) {
        this.repository = repository;
        this.bankAccountRepository = bankAccountRepository;
        this.settingsRepository = settingsRepository;
        this.auditService = auditService;
    }

    // --- Reads -------------------------------------------------------------

    /** Every method (including inactive) with its usage count, for the Settings page. */
    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> listAll() {
        Map<String, Long> usage = new HashMap<>();
        for (PaymentMethodRepository.UsageCount row : repository.usageCounts()) {
            usage.put(row.getCode().toUpperCase(Locale.ROOT), row.getTotal());
        }
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(m -> PaymentMethodResponse.from(m, usage.getOrDefault(m.getCode().toUpperCase(Locale.ROOT), 0L)))
                .toList();
    }

    /** Active methods for order entry, each with the account to pre-select when it is linked. */
    @Transactional(readOnly = true)
    public List<PaymentMethodOption> listActiveOptions() {
        return repository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(m -> PaymentMethodOption.from(m, linkedAccountId(m)))
                .toList();
    }

    /**
     * Resolves the method to use for a payment. A blank code means "the default method"; an
     * unknown or inactive code is rejected so a deleted/disabled method cannot be used.
     */
    @Transactional(readOnly = true)
    public PaymentMethod requireUsable(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.isEmpty()) {
            return repository.findFirstByActiveTrueAndDefaultMethodTrueOrderBySortOrderAscIdAsc()
                    .or(() -> repository.findByActiveTrueOrderBySortOrderAscIdAsc().stream().findFirst())
                    .orElseThrow(() -> new ValidationException(
                            "No payment method is configured. Ask an admin to add one in Settings."));
        }
        PaymentMethod method = repository.findByCodeIgnoreCase(trimmed)
                .orElseThrow(() -> new ValidationException("Payment method \"" + trimmed + "\" does not exist."));
        if (!method.isActive()) {
            throw new ValidationException("Payment method \"" + method.getLabel()
                    + "\" is no longer available. Choose another payment method.");
        }
        return method;
    }

    /**
     * The receiving account to pre-select for a linked method, or null when the method is not
     * linked or no active account of that purpose exists.
     *
     * <p>For {@link BankAccountType#SHOPIFY_SETTLEMENT} the account configured as the default
     * Shopify settlement account in Settings wins; otherwise the default-flagged, then first,
     * active account of that purpose is used.
     */
    @Transactional(readOnly = true)
    public Long linkedAccountId(PaymentMethod method) {
        BankAccountType type = method == null ? null : method.getLinkedAccountType();
        if (type == null) {
            return null;
        }
        return findLinkedAccount(type).map(BankAccount::getId).orElse(null);
    }

    /**
     * Where money that arrives through a purpose-specific channel is booked: the payment-method
     * code to stamp and the receiving account (id plus the display snapshots a payment row keeps).
     *
     * @param methodCode  code of the method linked to the purpose, or null when none exists
     * @param accountId   receiving account
     * @param accountName display name snapshot
     * @param accountMasked masked account number snapshot (may be null for UPI-only accounts)
     */
    public record LinkedPaymentTarget(String methodCode, Long accountId, String accountName,
                                      String accountMasked) {
    }

    /**
     * The target for payments of the given purpose, or empty when no active account of that
     * purpose is set up. Never throws: it is called from the Shopify import, where a
     * configuration gap must not stop a real customer order from being saved.
     */
    @Transactional(readOnly = true)
    public Optional<LinkedPaymentTarget> linkedTarget(BankAccountType type) {
        if (type == null) {
            return Optional.empty();
        }
        return findLinkedAccount(type).map(account -> new LinkedPaymentTarget(
                repository.findFirstByLinkedAccountTypeOrderByActiveDescSortOrderAscIdAsc(type)
                        .map(PaymentMethod::getCode)
                        .orElse(null),
                account.getId(),
                account.getDisplayName(),
                account.maskedAccountNumber()));
    }

    /**
     * Account resolution shared by order entry and the Shopify import. For
     * {@link BankAccountType#SHOPIFY_SETTLEMENT} the account chosen in Settings wins; otherwise
     * the default-flagged, then first, active account of that purpose is used.
     */
    private Optional<BankAccount> findLinkedAccount(BankAccountType type) {
        if (type == BankAccountType.SHOPIFY_SETTLEMENT) {
            Optional<BankAccount> configured = settingsRepository.findById(AppSettings.SINGLETON_ID)
                    .map(AppSettings::getShopifySettlementAccountId)
                    .flatMap(bankAccountRepository::findById)
                    .filter(BankAccount::isActive);
            if (configured.isPresent()) {
                return configured;
            }
        }
        List<BankAccount> accounts = bankAccountRepository
                .findByActiveTrueAndAccountTypeInOrderBySortOrderAscIdAsc(List.of(type));
        return accounts.stream().filter(BankAccount::isDefaultAccount).findFirst()
                .or(() -> accounts.stream().findFirst());
    }

    // --- Writes ------------------------------------------------------------

    @Transactional
    public PaymentMethodResponse create(PaymentMethodRequest request) {
        String label = cleanLabel(request.label());
        requireUniqueLabel(label, null);
        PaymentMethod method = new PaymentMethod(uniqueCode(label), label);
        apply(method, request);
        if (method.isDefaultMethod()) {
            requireActiveDefault(method);
            clearDefaultsExcept(null);
        }
        PaymentMethod saved = repository.save(method);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS, String.valueOf(saved.getId()),
                "Created payment method " + saved.getLabel() + " (" + saved.getCode() + ")");
        return PaymentMethodResponse.from(saved, 0L);
    }

    @Transactional
    public PaymentMethodResponse update(Long id, PaymentMethodRequest request) {
        PaymentMethod method = find(id);
        String label = cleanLabel(request.label());
        requireUniqueLabel(label, id);
        if (method.isDefaultMethod() && !request.defaultMethod()) {
            throw new ValidationException(
                    "There must always be a default payment method. Make another method the default instead.");
        }
        method.setLabel(label);
        apply(method, request);
        if (method.isDefaultMethod()) {
            requireActiveDefault(method);
            clearDefaultsExcept(id);
        }
        if (!method.isActive() && noOtherActive(id)) {
            throw new ValidationException("At least one payment method must stay active.");
        }
        PaymentMethod saved = repository.save(method);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS, String.valueOf(saved.getId()),
                "Updated payment method " + saved.getLabel() + " (" + saved.getCode() + ")");
        return PaymentMethodResponse.from(saved, repository.countUsage(saved.getCode()));
    }

    /** Makes an active method the default and clears the flag from the rest. */
    @Transactional
    public PaymentMethodResponse makeDefault(Long id) {
        PaymentMethod method = find(id);
        if (!method.isActive()) {
            throw new ValidationException("Activate \"" + method.getLabel() + "\" before making it the default.");
        }
        clearDefaultsExcept(id);
        method.setDefaultMethod(true);
        PaymentMethod saved = repository.save(method);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS, String.valueOf(saved.getId()),
                "Set default payment method to " + saved.getLabel());
        return PaymentMethodResponse.from(saved, repository.countUsage(saved.getCode()));
    }

    /**
     * Deletes a method that has never been used. Payments keep the code as plain text, so a used
     * method is refused: deactivate it instead and its history stays readable.
     */
    @Transactional
    public void delete(Long id) {
        PaymentMethod method = find(id);
        if (method.isDefaultMethod()) {
            throw new ValidationException(
                    "The default payment method can't be deleted. Make another method the default first.");
        }
        long used = repository.countUsage(method.getCode());
        if (used > 0) {
            throw new ValidationException("\"" + method.getLabel() + "\" has been used on " + used
                    + " payment" + (used == 1 ? "" : "s") + ", so it can't be deleted. Deactivate it instead.");
        }
        repository.delete(method);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS, String.valueOf(id),
                "Deleted payment method " + method.getLabel() + " (" + method.getCode() + ")");
    }

    // --- Helpers -----------------------------------------------------------

    private PaymentMethod find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment method " + id + " does not exist."));
    }

    private static void apply(PaymentMethod method, PaymentMethodRequest request) {
        method.setLinkedAccountType(request.linkedAccountType());
        // An auto-selected account is meaningless unless an account is required.
        method.setRequiresAccount(request.requiresAccount() || request.linkedAccountType() != null);
        method.setActive(request.active());
        method.setDefaultMethod(request.defaultMethod());
        method.setSortOrder(Math.max(0, request.sortOrder()));
    }

    private static void requireActiveDefault(PaymentMethod method) {
        if (!method.isActive()) {
            throw new ValidationException("The default payment method must be active.");
        }
    }

    private void requireUniqueLabel(String label, Long selfId) {
        repository.findByLabelIgnoreCase(label).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ValidationException("A payment method named \"" + existing.getLabel() + "\" already exists.");
            }
        });
    }

    private boolean noOtherActive(Long selfId) {
        return repository.findAll().stream().noneMatch(m -> m.isActive() && !m.getId().equals(selfId));
    }

    private void clearDefaultsExcept(Long keepId) {
        repository.findAll().forEach(m -> {
            if (m.isDefaultMethod() && (keepId == null || !m.getId().equals(keepId))) {
                m.setDefaultMethod(false);
            }
        });
    }

    private static String cleanLabel(String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) {
            throw new ValidationException("A payment method needs a name.");
        }
        return trimmed;
    }

    /** UPPER_SNAKE key built from the label, made unique with a numeric suffix. */
    private String uniqueCode(String label) {
        String base = label.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = "METHOD";
        }
        if (base.length() > CODE_MAX) {
            base = base.substring(0, CODE_MAX).replaceAll("_+$", "");
        }
        String candidate = base;
        int n = 2;
        while (repository.existsByCodeIgnoreCase(candidate)) {
            String suffix = "_" + n++;
            String head = base.length() + suffix.length() > CODE_MAX
                    ? base.substring(0, CODE_MAX - suffix.length()) : base;
            candidate = head + suffix;
        }
        return candidate;
    }
}
