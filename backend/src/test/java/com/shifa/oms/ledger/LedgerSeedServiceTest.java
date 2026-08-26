package com.shifa.oms.ledger;

import com.shifa.oms.ledger.autopost.ControlAccount;
import com.shifa.oms.ledger.autopost.ControlAccountResolver;
import com.shifa.oms.ledger.domain.AccountNature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Seed / configuration smoke test for {@link LedgerSeedService} (General Ledger, Reqs 1.6, 2.5).
 *
 * <p>This is a plain JUnit 5 unit test (not a jqwik property test), so {@link MockitoExtension} is
 * used. Per the project's Java 25 gotcha (Mockito cannot mock concrete classes) only the Spring Data
 * repository <em>interfaces</em> are mocked; {@link ControlAccountResolver} is a concrete collaborator
 * and is therefore built as a <strong>real</strong> instance over the mocked
 * {@link LedgerAccountRepository}.
 *
 * <p>The mocked repositories are wired as a tiny in-memory Chart of Accounts: {@code save(...)}
 * captures the entity (assigning it a synthetic id) and the finders read back from the captured lists,
 * so the seed logic runs end-to-end without a database. The tests assert:
 * <ul>
 *   <li>on an EMPTY chart, {@link LedgerSeedService#ensureSeeded()} creates all five
 *       {@link AccountNature} values among the seeded groups and every one of the nine
 *       {@link ControlAccount} control ledgers (Reqs 1.6, 2.5);</li>
 *   <li>the control-account &rarr; ledger-id mapping resolves for all nine control accounts after
 *       seeding;</li>
 *   <li>on a NON-empty chart, {@code ensureSeeded()} does NOT re-seed (no group/ledger saves) and only
 *       warms the control-account cache.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LedgerSeedServiceTest {

    @Mock
    private AccountGroupRepository accountGroupRepository;

    @Mock
    private LedgerAccountRepository ledgerAccountRepository;

    // --- EMPTY chart: seeds all natures + all control ledgers -------------------------------------

    @Test
    void seedsAllFiveNaturesAndEveryControlLedgerOnAnEmptyChart() {
        // In-memory capture of what the seed persists.
        List<AccountGroup> groups = new ArrayList<>();
        List<LedgerAccount> ledgers = new ArrayList<>();
        AtomicLong groupSeq = new AtomicLong(0);
        AtomicLong ledgerSeq = new AtomicLong(0);

        // Empty Chart of Accounts -> the first-initialisation guard fires.
        when(accountGroupRepository.count()).thenReturn(0L);

        // save captures the group (assigning a synthetic id, since JPA would).
        when(accountGroupRepository.save(any(AccountGroup.class))).thenAnswer(inv -> {
            AccountGroup g = inv.getArgument(0);
            ReflectionTestUtils.setField(g, "id", groupSeq.incrementAndGet());
            groups.add(g);
            return g;
        });
        // existence + root listing read back from the captured groups.
        when(accountGroupRepository.existsByParentGroupIdIsNullAndName(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return groups.stream().anyMatch(g -> g.getParentGroupId() == null && g.getName().equals(name));
        });
        when(accountGroupRepository.findByParentGroupIdIsNull()).thenAnswer(inv ->
                groups.stream().filter(g -> g.getParentGroupId() == null).collect(Collectors.toList()));

        // ledger save captures + control-key lookup reads back from the captured ledgers.
        when(ledgerAccountRepository.save(any(LedgerAccount.class))).thenAnswer(inv -> {
            LedgerAccount l = inv.getArgument(0);
            ReflectionTestUtils.setField(l, "id", ledgerSeq.incrementAndGet());
            ledgers.add(l);
            return l;
        });
        when(ledgerAccountRepository.findByControlKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return ledgers.stream().filter(l -> key.equals(l.getControlKey())).findFirst();
        });

        // Real resolver over the mocked repository (concrete class -> not mockable on Java 25).
        ControlAccountResolver resolver = new ControlAccountResolver(ledgerAccountRepository);
        LedgerSeedService seedService =
                new LedgerSeedService(accountGroupRepository, ledgerAccountRepository, resolver);

        seedService.ensureSeeded();

        // Every one of the five natures appears among the seeded groups (Req 1.6).
        for (AccountNature nature : AccountNature.values()) {
            assertThat(groups).as("a seeded group of nature %s exists", nature)
                    .anyMatch(g -> g.getNature() == nature);
        }

        // Every required control ledger exists, keyed by its control_key (Req 2.5).
        List<String> seededKeys = ledgers.stream()
                .map(LedgerAccount::getControlKey)
                .collect(Collectors.toList());
        for (ControlAccount control : ControlAccount.values()) {
            assertThat(seededKeys).as("a control ledger for %s exists", control.key())
                    .contains(control.key());
        }
        assertThat(ControlAccount.values()).hasSize(9); // all nine control accounts covered

        // The control-account -> ledger-id mapping resolves for all nine after seeding.
        for (ControlAccount control : ControlAccount.values()) {
            assertThat(seedService.controlLedgerId(control))
                    .as("resolved ledger id for %s", control.key())
                    .isNotNull();
        }
        assertThat(seedService.controlLedgerMapping()).hasSize(ControlAccount.values().length);
    }

    // --- NON-empty chart: does NOT re-seed, only warms the cache ----------------------------------

    @Test
    void doesNotReSeedWhenChartIsNotEmptyAndOnlyWarmsTheCache() {
        // A non-empty Chart of Accounts -> the guard skips seeding.
        when(accountGroupRepository.count()).thenReturn(5L);

        // Pre-existing control ledgers (already seeded by V55 in a real system).
        List<LedgerAccount> existing = new ArrayList<>();
        long id = 0;
        for (ControlAccount control : ControlAccount.values()) {
            LedgerAccount ledger = new LedgerAccount(control.key(), 1L, control.key(), true);
            ReflectionTestUtils.setField(ledger, "id", ++id);
            existing.add(ledger);
        }
        when(ledgerAccountRepository.findByControlKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return existing.stream().filter(l -> key.equals(l.getControlKey())).findFirst();
        });

        ControlAccountResolver resolver = new ControlAccountResolver(ledgerAccountRepository);
        LedgerSeedService seedService =
                new LedgerSeedService(accountGroupRepository, ledgerAccountRepository, resolver);

        seedService.ensureSeeded();

        // No re-seeding happened: no group or ledger was saved.
        verify(accountGroupRepository, never()).save(any(AccountGroup.class));
        verify(ledgerAccountRepository, never()).save(any(LedgerAccount.class));

        // Only the cache was warmed: the full control-account mapping is resolvable.
        assertThat(seedService.controlLedgerMapping()).hasSize(ControlAccount.values().length);
        for (ControlAccount control : ControlAccount.values()) {
            assertThat(seedService.controlLedgerId(control)).isNotNull();
        }
    }
}
