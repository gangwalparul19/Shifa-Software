package com.shifa.oms.ledger;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.ledger.domain.AccountNature;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link ChartOfAccountsService} name-uniqueness (General Ledger, design
 * Correctness Property 3).
 *
 * <p>Feature: general-ledger-accounting, Property 3: Names are unique within their parent.
 *
 * <p>Property statement: for any parent group with existing children, creating another child group
 * with a name that already exists under that parent is rejected; and for any account group with
 * existing ledgers, creating another ledger with a name that already exists under that group is
 * rejected. A name that already exists under a <em>different</em> parent, and a genuinely new name,
 * are both accepted.
 *
 * <p><b>Validates: Requirements 1.5, 2.3</b>
 *
 * <p>This is a service-level property test. Per the Java 25 gotcha (Mockito cannot mock concrete
 * classes), the repository <em>interfaces</em> are mocked with Mockito and backed by simple
 * in-memory maps so that the derived {@code existsBy...} finders enforce exactly the uniqueness the
 * real database unique constraints ({@code (parent_group_id, name)} and {@code (account_group_id,
 * name)}) would.
 */
class ChartOfAccountsUniquenessPropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 3: Names are unique within their parent
    // **Validates: Requirements 1.5, 2.3**
    // ---------------------------------------------------------------------------------------------

    /**
     * Req 1.5: for a parent group that already has children, creating another child with an existing
     * name is rejected; the same name under a different parent and a brand-new name are accepted.
     */
    @Property(tries = 200)
    void duplicateChildGroupNameUnderSameParentIsRejected(
            @ForAll("distinctNames") List<String> existingNames,
            @ForAll("names") String freshName) {
        Assume.that(!existingNames.contains(freshName));

        Coa coa = new Coa();
        AccountGroup parentP = coa.service.createGroup("ParentOne", AccountNature.ASSET, null);
        AccountGroup parentQ = coa.service.createGroup("ParentTwo", AccountNature.LIABILITY, null);

        // Seed the parent with its existing children.
        for (String name : existingNames) {
            coa.service.createGroup(name, null, parentP.getId());
        }

        String duplicate = existingNames.get(existingNames.size() - 1);

        // Re-creating an existing child name under the SAME parent is rejected (Req 1.5).
        assertThatThrownBy(() -> coa.service.createGroup(duplicate, null, parentP.getId()))
                .isInstanceOf(ValidationException.class);

        // The SAME name under a DIFFERENT parent is accepted (uniqueness is scoped to the parent).
        AccountGroup sibling = coa.service.createGroup(duplicate, null, parentQ.getId());
        assertThat(sibling.getId()).isNotNull();
        assertThat(sibling.getParentGroupId()).isEqualTo(parentQ.getId());

        // A brand-new name under the same parent is accepted.
        AccountGroup fresh = coa.service.createGroup(freshName, null, parentP.getId());
        assertThat(fresh.getId()).isNotNull();
        assertThat(fresh.getParentGroupId()).isEqualTo(parentP.getId());
    }

    /**
     * Req 2.3: for an account group that already has ledgers, creating another ledger with an
     * existing name is rejected; the same name under a different group and a brand-new name are
     * accepted.
     */
    @Property(tries = 200)
    void duplicateLedgerNameUnderSameGroupIsRejected(
            @ForAll("distinctNames") List<String> existingNames,
            @ForAll("names") String freshName) {
        Assume.that(!existingNames.contains(freshName));

        Coa coa = new Coa();
        AccountGroup groupA = coa.service.createGroup("GroupA", AccountNature.INCOME, null);
        AccountGroup groupB = coa.service.createGroup("GroupB", AccountNature.EXPENSE, null);

        // Seed group A with its existing ledgers.
        for (String name : existingNames) {
            coa.service.createLedger(name, groupA.getId());
        }

        String duplicate = existingNames.get(existingNames.size() - 1);

        // Re-creating an existing ledger name under the SAME group is rejected (Req 2.3).
        assertThatThrownBy(() -> coa.service.createLedger(duplicate, groupA.getId()))
                .isInstanceOf(ValidationException.class);

        // The SAME name under a DIFFERENT group is accepted (uniqueness is scoped to the group).
        LedgerAccount other = coa.service.createLedger(duplicate, groupB.getId());
        assertThat(other.getId()).isNotNull();
        assertThat(other.getAccountGroupId()).isEqualTo(groupB.getId());

        // A brand-new name under the same group is accepted.
        LedgerAccount fresh = coa.service.createLedger(freshName, groupA.getId());
        assertThat(fresh.getId()).isNotNull();
        assertThat(fresh.getAccountGroupId()).isEqualTo(groupA.getId());
    }

    // --- Generators ------------------------------------------------------------------------------

    /** A single non-blank alphabetic name (alpha-only avoids trim/whitespace-collision confounds). */
    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }

    /** A non-empty list of distinct alphabetic names to seed as existing children/ledgers. */
    @Provide
    Arbitrary<List<String>> distinctNames() {
        return names().list().ofMinSize(1).ofMaxSize(6)
                .map(list -> (List<String>) new ArrayList<>(new LinkedHashSet<>(list)))
                .filter(list -> !list.isEmpty());
    }

    // --- In-memory Chart-of-Accounts fixture (mocked repository interfaces) -----------------------

    /**
     * Builds a {@link ChartOfAccountsService} over Mockito-mocked repository <em>interfaces</em>
     * backed by simple maps. The {@code save} stubs assign a synthetic identity (mirroring the DB
     * {@code IDENTITY} column) and the {@code existsBy...} stubs enforce the same
     * name-within-parent / name-within-group uniqueness the real unique constraints would.
     */
    static final class Coa {
        final Map<Long, AccountGroup> groups = new ConcurrentHashMap<>();
        final Map<Long, LedgerAccount> ledgers = new ConcurrentHashMap<>();
        final AtomicLong groupSeq = new AtomicLong();
        final AtomicLong ledgerSeq = new AtomicLong();
        final ChartOfAccountsService service;

        Coa() {
            AccountGroupRepository groupRepo = mock(AccountGroupRepository.class);
            LedgerAccountRepository ledgerRepo = mock(LedgerAccountRepository.class);
            VoucherLineRepository lineRepo = mock(VoucherLineRepository.class);

            when(groupRepo.save(any(AccountGroup.class))).thenAnswer(inv -> {
                AccountGroup g = inv.getArgument(0);
                if (g.getId() == null) {
                    assignId(g, groupSeq.incrementAndGet());
                }
                groups.put(g.getId(), g);
                return g;
            });
            when(groupRepo.findById(anyLong())).thenAnswer(inv ->
                    Optional.ofNullable(groups.get(inv.<Long>getArgument(0))));
            when(groupRepo.existsById(anyLong())).thenAnswer(inv ->
                    groups.containsKey(inv.<Long>getArgument(0)));
            when(groupRepo.findAll()).thenAnswer(inv -> new ArrayList<>(groups.values()));
            when(groupRepo.existsByParentGroupIdAndName(anyLong(), any())).thenAnswer(inv -> {
                Long parentId = inv.getArgument(0);
                String name = inv.getArgument(1);
                return groups.values().stream().anyMatch(g ->
                        parentId.equals(g.getParentGroupId()) && name.equals(g.getName()));
            });
            when(groupRepo.existsByParentGroupIdIsNullAndName(any())).thenAnswer(inv -> {
                String name = inv.getArgument(0);
                return groups.values().stream().anyMatch(g ->
                        g.getParentGroupId() == null && name.equals(g.getName()));
            });

            when(ledgerRepo.save(any(LedgerAccount.class))).thenAnswer(inv -> {
                LedgerAccount l = inv.getArgument(0);
                if (l.getId() == null) {
                    assignId(l, ledgerSeq.incrementAndGet());
                }
                ledgers.put(l.getId(), l);
                return l;
            });
            when(ledgerRepo.existsByAccountGroupIdAndName(anyLong(), any())).thenAnswer(inv -> {
                Long groupId = inv.getArgument(0);
                String name = inv.getArgument(1);
                return ledgers.values().stream().anyMatch(l ->
                        groupId.equals(l.getAccountGroupId()) && name.equals(l.getName()));
            });

            service = new ChartOfAccountsService(groupRepo, ledgerRepo, lineRepo);
        }
    }

    /** Reflectively set the generated {@code id} on a persisted entity (no public setter exists). */
    private static void assignId(Object entity, long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not assign id on " + entity.getClass(), e);
        }
    }
}
