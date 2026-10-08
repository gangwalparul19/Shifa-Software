package com.shifa.oms.adminnotification;

import com.shifa.oms.auth.Role;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test that a role-addressed in-app notification reaches exactly
 * that role's active users (design §3.3, §5.2, §Correctness Properties (19)).
 *
 * Feature: role-based-order-workflow, Property 19: Role-addressed in-app
 * notifications reach exactly that role.
 *
 * **Validates: Requirements 13.4**
 *
 * <p>Pure and in-memory: exercises the {@link StaffNotificationVisibility} rule
 * (which the repository query mirrors) against a randomly generated population of
 * active/inactive users, with no Spring, database, or Mockito mocks of concrete
 * classes. Each {@code @Property} runs the jqwik default of 1000 tries (≥ 100).
 */
class RoleAddressedFanoutPropertyTest {

    /** A test user: id, role, and whether their login is active. */
    private record TestUser(long id, Role role, boolean active) {
    }

    // Feature: role-based-order-workflow, Property 19: Role-addressed in-app notifications reach exactly that role
    // **Validates: Requirements 13.4**
    @Property
    void roleAddressedReachesExactlyActiveUsersOfThatRole(
            @ForAll("users") List<TestUser> population,
            @ForAll("staffRoles") Role addressedRole) {
        List<TestUser> users = withUniqueIds(population);

        // A notification addressed to a role (no specific user).
        Set<Long> reached = users.stream()
                .filter(u -> u.active()
                        && StaffNotificationVisibility.reaches(addressedRole, null, u.role(), u.id()))
                .map(TestUser::id)
                .collect(Collectors.toSet());

        Set<Long> expected = users.stream()
                .filter(u -> u.active() && u.role() == addressedRole)
                .map(TestUser::id)
                .collect(Collectors.toSet());

        // Reaches every active user of that role, and no user of another role.
        assertThat(reached).isEqualTo(expected);
    }

    // Feature: role-based-order-workflow, Property 19: Role-addressed in-app notifications reach exactly that role
    // **Validates: Requirements 13.4**
    @Property
    void userAddressedReachesOnlyThatUser(
            @ForAll("users") List<TestUser> population) {
        List<TestUser> users = withUniqueIds(population);
        long addressed = users.get(0).id();

        Set<Long> reached = users.stream()
                .filter(u -> StaffNotificationVisibility.reaches(null, addressed, u.role(), u.id()))
                .map(TestUser::id)
                .collect(Collectors.toSet());

        // A user-addressed notification reaches exactly the addressed id (regardless
        // of role); no other user sees it.
        assertThat(reached).containsExactly(addressed);
    }

    // Feature: role-based-order-workflow, Property 19: Role-addressed in-app notifications reach exactly that role
    // **Validates: Requirements 13.4**
    @Property
    void legacyBroadcastReachesOnlyAdmins(
            @ForAll("users") List<TestUser> population) {
        List<TestUser> users = withUniqueIds(population);

        Set<Long> reached = users.stream()
                .filter(u -> u.active()
                        && StaffNotificationVisibility.reaches(null, null, u.role(), u.id()))
                .map(TestUser::id)
                .collect(Collectors.toSet());

        Set<Long> expectedAdmins = users.stream()
                .filter(u -> u.active() && u.role() == Role.ADMIN)
                .map(TestUser::id)
                .collect(Collectors.toSet());

        // Legacy admin broadcasts (both recipient fields null) reach exactly active admins.
        assertThat(reached).isEqualTo(expectedAdmins);
    }

    /** Reassigns sequential unique ids so generated populations have distinct users. */
    private static List<TestUser> withUniqueIds(List<TestUser> population) {
        List<TestUser> out = new java.util.ArrayList<>(population.size());
        long id = 1;
        for (TestUser u : population) {
            out.add(new TestUser(id++, u.role(), u.active()));
        }
        return out;
    }

    @Provide
    Arbitrary<TestUser> user() {
        Arbitrary<Role> roles = Arbitraries.of(Role.values());
        Arbitrary<Boolean> active = Arbitraries.of(true, false);
        return Combinators.combine(roles, active)
                .as((role, isActive) -> new TestUser(0L, role, isActive));
    }

    @Provide
    Arbitrary<List<TestUser>> users() {
        return user().list().ofMinSize(1).ofMaxSize(40);
    }

    @Provide
    Arbitrary<Role> staffRoles() {
        return Arbitraries.of(Role.ADMIN, Role.ACCOUNTANT, Role.SALESPERSON, Role.PACKING_USER);
    }
}
