package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.StaffProfileResponse;
import com.shifa.oms.auth.dto.UpdateStaffProfileRequest;
import com.shifa.oms.auth.dto.VerifyStaffRequest;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.platform.storage.ImageCompressor;
import com.shifa.oms.platform.storage.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Staff onboarding &amp; ID-verification application service.
 *
 * <p>Backs the ADMIN-only {@code /api/admin/staff} API: the "All salespeople"
 * directory, capturing a staff member's full profile, uploading their ID proof
 * to the pluggable {@link StorageService} (only the opaque key is persisted on
 * the {@link User}), and recording a verify/reject decision.
 *
 * <p>Kept deliberately separate from {@link AdminUserService} (which owns
 * username/role/password/active) so the credential-management flow and its
 * guardrails are untouched. Only non-customer staff rows are addressable here.
 */
@Service
public class StaffProfileService {

    /** Logical key prefix for uploaded staff ID documents in the object store. */
    private static final String ID_PROOF_PREFIX = "staff/id-proofs";

    /** Logical key prefix for uploaded staff profile photos in the object store. */
    private static final String PROFILE_IMAGE_PREFIX = "staff/profile";

    /** Longest-edge cap (px) for a stored ID-proof image; PDFs are stored as-is. */
    private static final int ID_PROOF_MAX_DIMENSION = 1600;

    /** Longest-edge cap (px) for a stored profile photo. */
    private static final int PROFILE_IMAGE_MAX_DIMENSION = 600;

    /** JPEG re-encode quality for compressed images. */
    private static final float JPEG_QUALITY = 0.75f;

    private final UserRepository userRepository;
    private final StorageService storageService;
    private final ImageCompressor imageCompressor;
    private final CurrentUserService currentUserService;

    public StaffProfileService(UserRepository userRepository,
                               StorageService storageService,
                               ImageCompressor imageCompressor,
                               CurrentUserService currentUserService) {
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.imageCompressor = imageCompressor;
        this.currentUserService = currentUserService;
    }

    /** The "All salespeople" directory (SALESPERSON role, newest first). */
    @Transactional(readOnly = true)
    public List<StaffProfileResponse> listSalespeople() {
        return userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON).stream()
                .map(StaffProfileResponse::from)
                .toList();
    }

    /** A single staff member's full profile. */
    @Transactional(readOnly = true)
    public StaffProfileResponse get(Long id) {
        return StaffProfileResponse.from(requireStaff(id));
    }

    /** Captures/updates the staff member's onboarding profile fields. */
    @Transactional
    public StaffProfileResponse updateProfile(Long id, UpdateStaffProfileRequest request) {
        User user = requireStaff(id);
        user.setFullName(request.fullName().trim());
        user.setEmail(blankToNull(request.email()));
        user.setMobile(blankToNull(request.mobile()));
        user.setDateOfBirth(request.dateOfBirth());
        user.setAddress(blankToNull(request.address()));
        user.setJoinedOn(request.joinedOn());
        user.setIdProofType(request.idProofType());
        user.setIdProofNumber(blankToNull(request.idProofNumber()));
        return StaffProfileResponse.from(userRepository.save(user));
    }

    /**
     * Stores an uploaded ID document and links its storage key to the staff
     * member. A fresh document resets verification to {@link VerificationStatus#PENDING}
     * so it is reviewed again.
     */
    @Transactional
    public StaffProfileResponse storeIdProof(Long id, String originalFilename,
                                             String contentType, byte[] content) {
        User user = requireStaff(id);
        // Compress image proofs before storage to keep object size (and S3 cost) down;
        // PDFs are passed through unchanged.
        ImageCompressor.Result compressed = imageCompressor.compress(
                originalFilename, contentType, content, ID_PROOF_MAX_DIMENSION, JPEG_QUALITY);
        StorageService.StoredObjectRef ref = storageService.store(
                ID_PROOF_PREFIX, compressed.filename(), compressed.contentType(), compressed.content());
        user.setIdProofKey(ref.key());
        user.setVerificationStatus(VerificationStatus.PENDING);
        user.setVerifiedAt(null);
        user.setVerifiedBy(null);
        user.setVerificationNote(null);
        return StaffProfileResponse.from(userRepository.save(user));
    }

    /** Loads the stored ID document bytes for viewing/downloading. */
    @Transactional(readOnly = true)
    public StorageService.StoredObject loadIdProof(Long id) {
        User user = requireStaff(id);
        String key = user.getIdProofKey();
        if (key == null || key.isBlank()) {
            throw new ResourceNotFoundException("No ID proof has been uploaded for this staff member.");
        }
        return storageService.load(key)
                .orElseThrow(() -> new ResourceNotFoundException("The ID proof document could not be found."));
    }

    /**
     * Stores an uploaded profile photo (compressed) and links its storage key to
     * the staff member. Must be an image; PDFs/other are rejected upstream.
     */
    @Transactional
    public StaffProfileResponse storeProfileImage(Long id, String originalFilename,
                                                  String contentType, byte[] content) {
        User user = requireStaff(id);
        ImageCompressor.Result compressed = imageCompressor.compress(
                originalFilename, contentType, content, PROFILE_IMAGE_MAX_DIMENSION, JPEG_QUALITY);
        StorageService.StoredObjectRef ref = storageService.store(
                PROFILE_IMAGE_PREFIX, compressed.filename(), compressed.contentType(), compressed.content());
        user.setProfileImageKey(ref.key());
        return StaffProfileResponse.from(userRepository.save(user));
    }

    /** Loads the stored profile photo bytes for viewing. */
    @Transactional(readOnly = true)
    public StorageService.StoredObject loadProfileImage(Long id) {
        User user = requireStaff(id);
        String key = user.getProfileImageKey();
        if (key == null || key.isBlank()) {
            throw new ResourceNotFoundException("No profile photo has been uploaded for this staff member.");
        }
        return storageService.load(key)
                .orElseThrow(() -> new ResourceNotFoundException("The profile photo could not be found."));
    }

    /**
     * Records a verify/reject decision. The status must be {@code VERIFIED} or
     * {@code REJECTED}; verifying requires an uploaded document to review.
     */
    @Transactional
    public StaffProfileResponse setVerification(Long id, VerifyStaffRequest request) {
        User user = requireStaff(id);
        VerificationStatus status = request.status();
        if (status == VerificationStatus.PENDING) {
            throw new ValidationException("Verification status must be VERIFIED or REJECTED.");
        }
        if (status == VerificationStatus.VERIFIED
                && (user.getIdProofKey() == null || user.getIdProofKey().isBlank())) {
            throw new ValidationException("Upload an ID proof document before verifying this staff member.");
        }
        user.setVerificationStatus(status);
        user.setVerificationNote(blankToNull(request.note()));
        user.setVerifiedAt(LocalDateTime.now());
        user.setVerifiedBy(currentUserService.currentUser().map(AuthPrincipal::userId).orElse(null));
        return StaffProfileResponse.from(userRepository.save(user));
    }

    /** Resolves a user by id, rejecting missing rows and non-staff (customer) rows. */
    private User requireStaff(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " does not exist."));
        if (user.getRole() == Role.CUSTOMER) {
            throw new ValidationException("This account is not a staff member.");
        }
        return user;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
