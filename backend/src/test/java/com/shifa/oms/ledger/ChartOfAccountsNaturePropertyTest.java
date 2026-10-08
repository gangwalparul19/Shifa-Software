package com.shifa.oms.ledger;

import com.shifa.oms.ledger.domain.AccountNature;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link ChartOfAccountsService} — nature derivation through the
 * Chart-of-Accounts hierarchy (General Ledger, design Correctness Property 1).
 *
 * <p>Feature: general-ledger-accounting, Property 1: Account nature is derived from the group
 * hierarchy.
 *
 * <p>Property statement: for any account group and any ledger account or child group created under
 * it, the derived nature of the child group or ledger account equals the nature of its parent group.
 *
 * <p><b>Validates: Requirements 1.3, 2.2</b>
 *
 * <p>This is a <em>service-level</em> property: it exercises the real {@link ChartOfAccountsService}
 * over lightweight in-memory fakes of its repository <em>interfaces</em>. Per the project's Java 25
 * gotcha (concrete classes cannot be Mockito-mocked) the collaborators mocked here are the Spring
 * Data <em>interfaces</em> ({@link AccountGroupRepository}, {@link LedgerAccountRepository},
 * {@link VoucherLineRepository}); each is backed by a {@link HashMap} so {@code save}/{@code findById}
 * behave like a real store (ids are assigned on save, so a child can be created under a
 * just-persisted parent). Because a {@link LedgerAccount} deliberately stores <em>no</em> nature
 * (Req 2.2), its "derived nature" is resolved exactly as the system resolves it — by looking up the
 * owning group's nature.
 */
class ChartOfAccountsNaturePropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 1: Account nature is derived from the group hierarchy
    // **Validates: Requirements 1.3, 2.2**
    // ---------------------------------------------------------------------------------------------

    /**
     * Req 1.3: a child group created under a parent carries the <em>same</em> nature as its parent,
     * regardless of any nature argument supplied on the create call (the argument is ignored when a
     * parent is given). The rule holds transitively down a chain (grandchild == root).
     */
    @Property(tries = 200)
    void childGroupInheritsParentNature(@ForAll("natures") AccountNature parentNature,
                                        @ForAll("natures") AccountNature ignoredNature) {
        Fixture f = newFixture();
        ChartOfAccountsService service = f.service;

        AccountGroup root = service.createGroup("Root", parentNature, null);
        assertThat(root.getNature()).isEqualTo(parentNature);

        // The `ignoredNature` argument must be discarded: the child inherits the parent's nature.
        AccountGroup child = service.createGroup("Child", ignoredNature, root.getId());
        assertThat(child.getNature())
                .as("child group nature derived from parent")
                .isEqualTo(parentNature);

        // Nature inheritance is transitive down the hierarchy.
        AccountGroup grandChild = service.createGroup("GrandChild", ignoredNature, child.getId());
        assertThat(grandChild.getNature())
                .as("grandchild group nature derived from the (same-nature) chain")
                .isEqualTo(parentNature);
    }

    /**
     * Req 2.1 + 2.2: a ledger account records a reference to its owning group and stores no nature
     * of its own; its derived nature is therefore always the owning group's nature — whether the
     * group is a root or a nested child.
     */
    @Property(tries = 200)
    void ledgerNatureIsDerivedFromItsGroup(@ForAll("natures") AccountNature parentNature,
                                           @ForAll("natures") AccountNature ignoredNature) {
        Fixture f = newFixture();
        ChartOfAccountsService service = f.service;

        AccountGroup root = service.createGroup("Root", parentNature, null);
        AccountGroup child = service.createGroup("Child", ignoredNature, root.getId());

        LedgerAccount underRoot = service.createLedger("Cash", root.getId());
        LedgerAccount underChild = service.createLedger("Petty Cash", child.getId());

        // Req 2.1: the ledger references the group it was created under.
        assertThat(underRoot.getAccountGroupId()).isEqualTo(root.getId());
        assertThat(underChild.getAccountGroupId()).isEqualTo(child.getId());

        // Req 2.2: the ledger's derived nature equals its owning group's nature (which, for the
        // child group, is itself the inherited root nature).
        assertThat(f.derivedNature(underRoot))
                .as("ledger-under-root derived nature == root group nature")
                .isEqualTo(root.getNature());
        assertThat(f.derivedNature(underChild))
                .as("ledger-under-child derived nature == child group nature")
                .isEqualTo(child.getNature())
                .isEqualTo(parentNature);
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<AccountNature> natures() {
        return Arbitraries.of(AccountNature.values());
    }

    // --- In-memory repository fixture ------------------------------------------------------------

    /**
     * A {@link ChartOfAccountsService} wired over in-memory fakes of its repository interfaces.
     * The backing maps let a group/ledger be persisted (with an assigned id) and then read back,
     * so multi-step scenarios (create parent → create child under it → create ledger) work exactly
     * as they do against a real database.
     */
    private static final class Fixture {
        final ChartOfAccountsService service;
        final Map<Long, AccountGroup> groups;

        Fixture(ChartOfAccountsService service, Map<Long, AccountGroup> groups) {
            this.service = service;
            this.groups = groups;
        }

        /** Resolve a ledger's nature the way the system does — from its owning account group. */
        AccountNature derivedNature(LedgerAccount ledger) {
            AccountGroup group = groups.get(ledger.getAccountGroupId());
            assertThat(group).as("owning group of ledger must exist").isNotNull();
            return group.getNature();
        }
    }

    private static Fixture newFixture() {
        Map<Long, AccountGroup> groups = new HashMap<>();
        Map<Long, LedgerAccount> ledgers = new HashMap<>();
        AtomicLong groupSeq = new AtomicLong(0);
        AtomicLong ledgerSeq = new AtomicLong(0);

        AccountGroupRepository groupRepo = mock(AccountGroupRepository.class);
        LedgerAccountRepository ledgerRepo = mock(LedgerAccountRepository.class);
        VoucherLineRepository lineRepo = mock(VoucherLineRepository.class);

        when(groupRepo.save(any(AccountGroup.class))).thenAnswer(inv -> {
            AccountGroup g = inv.getArgument(0);
            if (getId(g) == null) {
                setId(g, groupSeq.incrementAndGet());
            }
            groups.put(getId(g), g);
            return g;
        });
        when(groupRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(groups.get(inv.<Long>getArgument(0))));
        when(groupRepo.existsById(anyLong()))
                .thenAnswer(inv -> groups.containsKey(inv.<Long>getArgument(0)));
        when(groupRepo.findAll()).thenAnswer(inv -> new ArrayList<>(groups.values()));
        when(groupRepo.existsByParentGroupIdIsNullAndName(anyString())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return groups.values().stream()
                    .anyMatch(g -> g.getParentGroupId() == null && g.getName().equals(name));
        });
        when(groupRepo.existsByParentGroupIdAndName(anyLong(), anyString())).thenAnswer(inv -> {
            Long parentId = inv.getArgument(0);
            String name = inv.getArgument(1);
            return groups.values().stream()
                    .anyMatch(g -> parentId.equals(g.getParentGroupId()) && g.getName().equals(name));
        });

        when(ledgerRepo.save(any(LedgerAccount.class))).thenAnswer(inv -> {
            LedgerAccount l = inv.getArgument(0);
            if (getId(l) == null) {
                setId(l, ledgerSeq.incrementAndGet());
            }
            ledgers.put(getId(l), l);
            return l;
        });
        when(ledgerRepo.existsByAccountGroupIdAndName(anyLong(), anyString())).thenAnswer(inv -> {
            Long groupId = inv.getArgument(0);
            String name = inv.getArgument(1);
            return ledgers.values().stream()
                    .anyMatch(l -> groupId.equals(l.getAccountGroupId()) && l.getName().equals(name));
        });

        ChartOfAccountsService service = new ChartOfAccountsService(groupRepo, ledgerRepo, lineRepo);
        return new Fixture(service, groups);
    }

    // --- id reflection helpers (JPA-generated id has no public setter) ---------------------------

    private static Long getId(Object entity) {
        return (Long) readField(entity, "id");
    }

    private static void setId(Object entity, Long id) {
        writeField(entity, "id", id);
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read field '" + fieldName + "'", e);
        }
    }

    private static void writeField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to write field '" + fieldName + "'", e);
        }
    }
}
