package com.shifa.oms.ledger;

import com.shifa.oms.ledger.domain.AccountNature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link ChartOfAccountsService} (General Ledger, Reqs 1.1, 2.1).
 *
 * <p>These complement the hierarchy/uniqueness/delete-guard <em>property</em> tests
 * ({@code ChartOfAccountsNaturePropertyTest}, {@code ...UniquenessPropertyTest},
 * {@code ...DeleteGuardPropertyTest}) with two concrete, worked examples of the happy path:
 * <ul>
 *   <li>creating a root account group under a given nature persists the group with that nature
 *       (Req 1.1);</li>
 *   <li>creating a ledger account under an existing group persists the ledger referencing that
 *       group (Req 2.1).</li>
 * </ul>
 *
 * <p>Per the project's Java 25 gotcha (Mockito cannot mock concrete classes) the collaborators
 * mocked here are the Spring Data repository <em>interfaces</em>
 * ({@link AccountGroupRepository}, {@link LedgerAccountRepository}, {@link VoucherLineRepository}).
 * {@link MockitoExtension} is fine here because this is a plain JUnit 5 test (not a jqwik property).
 */
@ExtendWith(MockitoExtension.class)
class ChartOfAccountsServiceTest {

    @Mock
    private AccountGroupRepository groupRepository;

    @Mock
    private LedgerAccountRepository ledgerRepository;

    @Mock
    private VoucherLineRepository voucherLineRepository;

    @InjectMocks
    private ChartOfAccountsService service;

    // --- Req 1.1: group creation under a nature ---------------------------------------------------

    @Test
    void createsRootAccountGroupWithTheGivenNature() {
        // save echoes back the entity it was asked to persist.
        when(groupRepository.save(any(AccountGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountGroup created = service.createGroup("Fixed Assets", AccountNature.ASSET, null);

        // The group is persisted with the supplied name and nature, as a root (no parent).
        assertThat(created.getName()).isEqualTo("Fixed Assets");
        assertThat(created.getNature()).isEqualTo(AccountNature.ASSET);
        assertThat(created.getParentGroupId()).isNull();

        // And what the service returned is exactly what was handed to the repository.
        ArgumentCaptor<AccountGroup> saved = ArgumentCaptor.forClass(AccountGroup.class);
        verify(groupRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Fixed Assets");
        assertThat(saved.getValue().getNature()).isEqualTo(AccountNature.ASSET);
        assertThat(saved.getValue().getParentGroupId()).isNull();
    }

    // --- Req 2.1: ledger creation under a group ---------------------------------------------------

    @Test
    void createsLedgerUnderAnExistingGroupReferencingThatGroup() {
        long groupId = 42L;
        // The owning group must exist so the ledger's nature can be derived from it (Req 2.2).
        when(groupRepository.existsById(groupId)).thenReturn(true);
        when(ledgerRepository.save(any(LedgerAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        LedgerAccount created = service.createLedger("Cash", groupId);

        // The ledger is persisted with the supplied name and a reference to its owning group.
        assertThat(created.getName()).isEqualTo("Cash");
        assertThat(created.getAccountGroupId()).isEqualTo(groupId);

        ArgumentCaptor<LedgerAccount> saved = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(ledgerRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Cash");
        assertThat(saved.getValue().getAccountGroupId()).isEqualTo(groupId);
    }
}
