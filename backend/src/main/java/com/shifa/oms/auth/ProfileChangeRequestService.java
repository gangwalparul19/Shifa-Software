package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.MyProfileResponse;
import com.shifa.oms.auth.dto.ProfileChangeRequestResponse;
import com.shifa.oms.auth.dto.StaffProfileResponse;
import com.shifa.oms.auth.dto.SubmitProfileChangeRequest;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Self-service staff profile edits with admin approval.
 *
 * <p>A signed-in staff member can view their own profile and submit a change
 * request; the request is queued and <strong>nothing on their {@link User} row
 * changes until an admin approves it</strong>. At most one
 * {@link ChangeRequestStatus#PENDING} request per user is kept — a new
 * submission replaces the pending one. Admin approval applies the proposed
 * values to the user; rejection leaves the user unchanged.
 *
 * <p>This is deliberately separate from {@link AdminUserService} (credentials)
 * and {@link StaffProfileService} (admin-driven profile/ID edits), so the
 * moderated self-service path does not touch those flows.
 */
@Service
public class ProfileChangeRequestService {

    private final UserRepository userRepository;
    private final ProfileChangeRequestRepository requestRepository;
    private final CurrentUserService currentUserService;

    public ProfileChangeRequestService(UserRepository userRepository,
                                        ProfileChangeRequestRepository requestRepository,
                                        CurrentUserService currentUserService) {
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.currentUserService = currentUserService;
    }

    // --- Employee self-service ---------------------------------------------

    /** The signed-in staff member's current profile + their pending request (if any). */
    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile() {
        User user = requireCurrentStaff();
        ProfileChangeRequestResponse pending = requestRepository
                .findFirstByUserIdAndStatus(user.getId(), ChangeRequestStatus.PENDING)
                .map(req -> ProfileChangeRequestResponse.from(req, user))
                .orElse(null);
        return new MyProfileResponse(StaffProfileResponse.from(user), pending);
    }

    /**
     * Submits (or replaces) the signed-in staff member's pending change request.
     * Does not modify the profile — it queues the values for admin approval.
     */
    @Transactional
    public ProfileChangeRequestResponse submitMyChangeRequest(SubmitProfileChangeRequest submission) {
        User user = requireCurrentStaff();
        ProfileChangeRequest request = requestRepository
                .findFirstByUserIdAndStatus(user.getId(), ChangeRequestStatus.PENDING)
                .orElseGet(() -> new ProfileChangeRequest(user.getId()));
        request.setFullName(blankToNull(submission.fullName()));
        request.setEmail(blankToNull(submission.email()));
        request.setMobile(blankToNull(submission.mobile()));
        request.setDateOfBirth(submission.dateOfBirth());
        request.setAddress(blankToNull(submission.address()));
        request.setIdProofType(submission.idProofType());
        request.setIdProofNumber(blankToNull(submission.idProofNumber()));
        request.setRequestNote(blankToNull(submission.requestNote()));
        request.setStatus(ChangeRequestStatus.PENDING);
        request.setRequestedAt(LocalDateTime.now());
        request.setReviewedAt(null);
        request.setReviewedBy(null);
        request.setReviewNote(null);
        return ProfileChangeRequestResponse.from(requestRepository.save(request), user);
    }

    // --- Admin approval queue ----------------------------------------------

    /** All pending change requests (oldest first) for the admin approval queue. */
    @Transactional(readOnly = true)
    public List<ProfileChangeRequestResponse> listPending() {
        return requestRepository.findByStatusOrderByRequestedAtAsc(ChangeRequestStatus.PENDING).stream()
                .map(req -> ProfileChangeRequestResponse.from(req, requireUser(req.getUserId())))
                .toList();
    }

    /** Count of requests awaiting approval (for a badge). */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return requestRepository.countByStatus(ChangeRequestStatus.PENDING);
    }

    /**
     * Approves a pending request — applying the proposed values to the user — or
     * rejects it (leaving the user unchanged). Only a PENDING request can be
     * reviewed. Returns the decided request; {@code applied} indicates approval.
     */
    @Transactional
    public ReviewResult review(Long requestId, ChangeRequestStatus decision, String note) {
        if (decision != ChangeRequestStatus.APPROVED && decision != ChangeRequestStatus.REJECTED) {
            throw new ValidationException("Decision must be APPROVED or REJECTED.");
        }
        ProfileChangeRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Change request " + requestId + " does not exist."));
        if (request.getStatus() != ChangeRequestStatus.PENDING) {
            throw new ValidationException("This change request has already been reviewed.");
        }
        User user = requireUser(request.getUserId());

        if (decision == ChangeRequestStatus.APPROVED) {
            // fullName is required on submission; the rest are applied as proposed
            // (a null proposed value intentionally clears the optional field).
            if (request.getFullName() != null && !request.getFullName().isBlank()) {
                user.setFullName(request.getFullName().trim());
            }
            user.setEmail(request.getEmail());
            user.setMobile(request.getMobile());
            user.setDateOfBirth(request.getDateOfBirth());
            user.setAddress(request.getAddress());
            user.setIdProofType(request.getIdProofType());
            user.setIdProofNumber(request.getIdProofNumber());
            userRepository.save(user);
        }

        request.setStatus(decision);
        request.setReviewNote(blankToNull(note));
        request.setReviewedAt(LocalDateTime.now());
        request.setReviewedBy(currentUserService.currentUser().map(AuthPrincipal::userId).orElse(null));
        requestRepository.save(request);

        return new ReviewResult(
                ProfileChangeRequestResponse.from(request, user),
                decision == ChangeRequestStatus.APPROVED);
    }

    /** Outcome of an admin review: the decided request + whether it was applied. */
    public record ReviewResult(ProfileChangeRequestResponse request, boolean applied) {
    }

    // --- Helpers ------------------------------------------------------------

    private User requireCurrentStaff() {
        AuthPrincipal principal = currentUserService.requireCurrentUser();
        User user = requireUser(principal.userId());
        if (user.getRole() == Role.CUSTOMER) {
            throw new ValidationException("Profile management is available to staff members only.");
        }
        return user;
    }

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " does not exist."));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
