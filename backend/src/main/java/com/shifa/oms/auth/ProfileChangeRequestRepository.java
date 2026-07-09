package com.shifa.oms.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository for {@link ProfileChangeRequest} entities. */
public interface ProfileChangeRequestRepository extends JpaRepository<ProfileChangeRequest, Long> {

    /** The user's current request in the given state, if any (e.g. the single PENDING one). */
    Optional<ProfileChangeRequest> findFirstByUserIdAndStatus(Long userId, ChangeRequestStatus status);

    /** All requests in a given state, oldest first — backs the admin approval queue. */
    List<ProfileChangeRequest> findByStatusOrderByRequestedAtAsc(ChangeRequestStatus status);

    /** Count of requests in a given state (e.g. pending-approval badge). */
    long countByStatus(ChangeRequestStatus status);
}
