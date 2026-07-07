package com.shifa.oms.auth;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Spring Data repository for {@link User} entities. */
public interface UserRepository extends JpaRepository<User, Long> {

    /** Finds a user by their unique username (used at login). */
    Optional<User> findByUsername(String username);

    /** Whether a user already exists with this username (registration duplicate guard). */
    boolean existsByUsername(String username);

    /** All users ordered for the admin management grid (newest first). */
    List<User> findAllByOrderByCreatedAtDescIdDesc();

    /**
     * Count of active users holding a given role — used to protect the last
     * remaining active {@link Role#ADMIN} from deactivation/demotion.
     */
    long countByRoleAndActiveTrue(Role role);

    /**
     * Case-insensitive search over registered {@link Role#CUSTOMER} accounts by
     * full name or mobile, ordered by name. Backs the admin global search
     * (ROADMAP 2.2 "Wave 2" customers group); the {@link Pageable} caps the
     * number of results.
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.role = com.shifa.oms.auth.Role.CUSTOMER
              AND (LOWER(u.fullName) LIKE CONCAT('%', LOWER(:q), '%')
                   OR u.mobile LIKE CONCAT('%', :q, '%'))
            ORDER BY u.fullName ASC
            """)
    List<User> searchCustomers(@Param("q") String q, Pageable pageable);

    /** Whether a registered {@link Role#CUSTOMER} account exists for the given mobile. */
    boolean existsByMobileAndRole(String mobile, Role role);

    /**
     * The subset of the given mobiles that belong to a registered
     * {@link Role#CUSTOMER} account. Backs the CRM "registered" flag: the customer
     * summaries batch-resolve which of their mobiles map to an account in one
     * query rather than one lookup per row.
     */
    @Query("""
            SELECT u.mobile FROM User u
            WHERE u.role = com.shifa.oms.auth.Role.CUSTOMER
              AND u.mobile IN :mobiles
            """)
    List<String> findCustomerMobilesIn(@Param("mobiles") Collection<String> mobiles);
}
