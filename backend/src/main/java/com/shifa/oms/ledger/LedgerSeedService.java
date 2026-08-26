package com.shifa.oms.ledger;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.ledger.autopost.ControlAccount;
import com.shifa.oms.ledger.autopost.ControlAccountResolver;
import com.shifa.oms.ledger.domain.AccountNature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Programmatic "on first initialisation" guard for the General Ledger module (Reqs 1.6, 2.5).
 *
 * <p>The actual seed <em>rows</em> — the five-nature default account groups and the mapped control
 * ledgers (each carrying its {@code control_key}) — are inserted by the convergent Flyway migration
 * {@code V55__ledger_seed.sql}, which runs in every environment. This service is the programmatic
 * counterpart the design's "on first initialisation" wording maps onto: it
 * <ul>
 *   <li>ensures the default groups + control ledgers exist, guarded by "are there any account
 *       groups?" so it only ever seeds a genuinely empty Chart of Accounts, and</li>
 *   <li>resolves and caches the {@link ControlAccount} → {@code ledger_accounts.id} mapping so
 *       auto-posting has a warm lookup at hand.</li>
 * </ul>
 *
 * <p><strong>Idempotency &amp; convergence with V55.</strong> {@link #ensureSeeded()} first checks the
 * guard {@code AccountGroupRepository.count() == 0}; when any group already exists (the normal case,
 * because V55 has already run via Flyway) the seed step is a no-op and only the cache is warmed, so no
 * row V55 already inserted is ever duplicated. Even inside the seed block each row is created only when
 * missing (groups keyed by their root name, control ledgers keyed by {@code control_key}), mirroring
 * V55's {@code WHERE NOT EXISTS} convergence so a partial state is safely completed rather than
 * duplicated.
 *
 * <p>The seed uses the entity constructors directly (not {@code ChartOfAccountsService}) so the seeded
 * rows carry {@code system_generated = true} and — for the control ledgers — their {@code control_key},
 * exactly as V55 writes them.
 */
@Service
public class LedgerSeedService {

    private static final Logger log = LoggerFactory.getLogger(LedgerSeedService.class);

    /** The five-nature default account groups (Req 1.6), all top-level (no parent), matching V55. */
    private static final List<SeedGroup> DEFAULT_GROUPS = List.of(
            new SeedGroup("Current Assets", AccountNature.ASSET),
            new SeedGroup("Fixed Assets", AccountNature.ASSET),
            new SeedGroup("Cash-in-Hand", AccountNature.ASSET),
            new SeedGroup("Bank Accounts", AccountNature.ASSET),
            new SeedGroup("Sundry Debtors", AccountNature.ASSET),
            new SeedGroup("Current Liabilities", AccountNature.LIABILITY),
            new SeedGroup("Duties & Taxes", AccountNature.LIABILITY),
            new SeedGroup("Sundry Creditors", AccountNature.LIABILITY),
            new SeedGroup("Sales Accounts", AccountNature.INCOME),
            new SeedGroup("Direct Income", AccountNature.INCOME),
            new SeedGroup("Indirect Income", AccountNature.INCOME),
            new SeedGroup("Purchase Accounts", AccountNature.EXPENSE),
            new SeedGroup("Direct Expenses", AccountNature.EXPENSE),
            new SeedGroup("Indirect Expenses", AccountNature.EXPENSE),
            new SeedGroup("Capital Account", AccountNature.EQUITY));

    /**
     * The mapped control ledgers (Req 2.5), each under its group (resolved by name) with its
     * {@code control_key}. Placement mirrors {@code V55__ledger_seed.sql} exactly — note that
     * {@code GST Input} sits under the ASSET group "Current Assets" (input GST is a recoverable asset),
     * so its derived nature is ASSET, per the V55 header note.
     */
    private static final List<SeedLedger> CONTROL_LEDGERS = List.of(
            new SeedLedger("Sundry Debtors", "Sundry Debtors", ControlAccount.SUNDRY_DEBTORS),
            new SeedLedger("Sundry Creditors", "Sundry Creditors", ControlAccount.SUNDRY_CREDITORS),
            new SeedLedger("Sales", "Sales Accounts", ControlAccount.SALES),
            new SeedLedger("Purchases", "Purchase Accounts", ControlAccount.PURCHASES),
            new SeedLedger("GST Output", "Duties & Taxes", ControlAccount.GST_OUTPUT),
            new SeedLedger("GST Input", "Current Assets", ControlAccount.GST_INPUT),
            new SeedLedger("Cash", "Cash-in-Hand", ControlAccount.CASH),
            new SeedLedger("Bank", "Bank Accounts", ControlAccount.BANK),
            new SeedLedger("General Expenses", "Indirect Expenses", ControlAccount.DEFAULT_EXPENSE));

    private final AccountGroupRepository accountGroupRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final ControlAccountResolver controlAccountResolver;

    /**
     * Cache of the resolved control-account → ledger-id mapping. Populated on warm-up (and lazily on
     * demand). This is a convenience cache for the seed/startup path; the authoritative, always-current
     * resolution stays in {@link ControlAccountResolver} (which deliberately does not cache, so a
     * re-pointed control ledger is reflected immediately).
     */
    private final Map<ControlAccount, Long> controlLedgerCache = new ConcurrentHashMap<>();

    public LedgerSeedService(AccountGroupRepository accountGroupRepository,
                             LedgerAccountRepository ledgerAccountRepository,
                             ControlAccountResolver controlAccountResolver) {
        this.accountGroupRepository = accountGroupRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.controlAccountResolver = controlAccountResolver;
    }

    /**
     * Ensure the default Chart of Accounts exists and warm the control-account cache.
     *
     * <p>Runs automatically once the application context is ready (in every profile, matching V55's
     * Flyway reach) and is also safe to call directly. Idempotent: the seed step only fires on a
     * genuinely empty Chart of Accounts ({@code AccountGroupRepository.count() == 0}); otherwise it is a
     * no-op and only the cache is (re)warmed, so it never duplicates a row V55 already inserted.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureSeeded() {
        // First-initialisation guard (design: "are there any account groups?"). When V55 has already
        // seeded (the normal case) this is a no-op; only a truly empty Chart of Accounts is seeded.
        if (accountGroupRepository.count() == 0) {
            log.info("No account groups found; seeding default General Ledger groups and control ledgers.");
            seedDefaultGroupsAndControlLedgers();
        }
        warmControlAccountCache();
    }

    /**
     * Create the default groups and control ledgers, creating only what is missing (convergent with
     * V55). Groups are matched by their root name; control ledgers by their {@code control_key}.
     */
    private void seedDefaultGroupsAndControlLedgers() {
        for (SeedGroup group : DEFAULT_GROUPS) {
            if (!accountGroupRepository.existsByParentGroupIdIsNullAndName(group.name())) {
                accountGroupRepository.save(new AccountGroup(group.name(), group.nature(), null, true));
            }
        }

        Map<String, Long> rootGroupIdByName = accountGroupRepository.findByParentGroupIdIsNull().stream()
                .collect(Collectors.toMap(AccountGroup::getName, AccountGroup::getId, (existing, ignored) -> existing));

        for (SeedLedger ledger : CONTROL_LEDGERS) {
            if (ledgerAccountRepository.findByControlKey(ledger.control().key()).isPresent()) {
                continue; // already present — convergent no-op
            }
            Long groupId = rootGroupIdByName.get(ledger.groupName());
            if (groupId == null) {
                // Should not happen (the group is seeded just above), but never post to a wrong account.
                log.warn("Skipping control ledger {} — group '{}' not found.",
                        ledger.control().key(), ledger.groupName());
                continue;
            }
            ledgerAccountRepository.save(
                    new LedgerAccount(ledger.name(), groupId, ledger.control().key(), true));
        }
    }

    /**
     * Resolve every {@link ControlAccount} to its mapped ledger id via {@link ControlAccountResolver}
     * and cache the result. A control role with no mapped ledger is logged and skipped rather than
     * failing startup — auto-posting will surface the missing mapping loudly when it actually needs it.
     */
    public void warmControlAccountCache() {
        controlLedgerCache.clear();
        for (ControlAccount control : ControlAccount.values()) {
            try {
                controlLedgerCache.put(control, controlAccountResolver.resolveLedgerId(control));
            } catch (ResourceNotFoundException ex) {
                log.warn("Control account {} has no mapped ledger during seed warm-up: {}",
                        control.key(), ex.getMessage());
            }
        }
    }

    /**
     * The resolved ledger id for a control role — served from the warm cache when present, otherwise
     * resolved fresh (and cached) via {@link ControlAccountResolver}.
     *
     * @param control the control role to resolve; must not be {@code null}
     * @return the mapped {@code ledger_accounts.id}
     * @throws ResourceNotFoundException when no ledger carries the role's {@code control_key}
     */
    public Long controlLedgerId(ControlAccount control) {
        Long cached = controlLedgerCache.get(control);
        if (cached != null) {
            return cached;
        }
        Long resolved = controlAccountResolver.resolveLedgerId(control);
        controlLedgerCache.put(control, resolved);
        return resolved;
    }

    /** An immutable snapshot of the currently cached control-account → ledger-id mapping. */
    public Map<ControlAccount, Long> controlLedgerMapping() {
        return Map.copyOf(controlLedgerCache);
    }

    /** A default account group to seed: a root group of a given nature. */
    private record SeedGroup(String name, AccountNature nature) {
    }

    /** A control ledger to seed under a named root group, carrying its {@link ControlAccount} key. */
    private record SeedLedger(String name, String groupName, ControlAccount control) {
    }
}
