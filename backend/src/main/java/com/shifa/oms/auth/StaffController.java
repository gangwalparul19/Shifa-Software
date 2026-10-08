package com.shifa.oms.auth;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.dto.ProfileChangeRequestResponse;
import com.shifa.oms.auth.dto.ReviewProfileChangeRequest;
import com.shifa.oms.auth.dto.StaffProfileResponse;
import com.shifa.oms.auth.dto.UpdateStaffProfileRequest;
import com.shifa.oms.auth.dto.VerifyStaffRequest;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.platform.storage.StorageService;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Admin staff onboarding &amp; ID verification ({@code /api/admin/staff}).
 *
 * <p>ADMIN-only (class-level method security). Provides the "All salespeople"
 * directory plus, for any staff member, capturing their full profile, uploading
 * and viewing their ID proof, and recording a verify/reject decision. Every
 * mutation writes an audit event. Credential management (username/role/password/
 * active) stays on {@link AdminUserController}.
 */
@RestController
@RequestMapping("/api/admin/staff")
@PreAuthorize("hasRole('ADMIN')")
public class StaffController {

    private final StaffProfileService staffProfileService;
    private final ProfileChangeRequestService changeRequestService;
    private final AuditService auditService;

    public StaffController(StaffProfileService staffProfileService,
                           ProfileChangeRequestService changeRequestService,
                           AuditService auditService) {
        this.staffProfileService = staffProfileService;
        this.changeRequestService = changeRequestService;
        this.auditService = auditService;
    }

    /** The "All salespeople" directory (newest first). */
    @GetMapping
    public List<StaffProfileResponse> listSalespeople() {
        return staffProfileService.listSalespeople();
    }

    /** A single staff member's full profile + verification state. */
    @GetMapping("/{id}")
    public StaffProfileResponse get(@PathVariable Long id) {
        return staffProfileService.get(id);
    }

    /** Captures/updates the staff member's onboarding profile fields. */
    @PutMapping("/{id}")
    public StaffProfileResponse updateProfile(@PathVariable Long id,
                                              @Valid @RequestBody UpdateStaffProfileRequest request) {
        StaffProfileResponse response = staffProfileService.updateProfile(id, request);
        auditService.record(AuditActions.STAFF_PROFILE_UPDATED, AuditActions.ENTITY_USER,
                String.valueOf(id), "Updated staff profile for " + response.username());
        return response;
    }

    /** Uploads (or replaces) the staff member's ID proof document. */
    @PostMapping(path = "/{id}/id-proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StaffProfileResponse uploadIdProof(@PathVariable Long id,
                                              @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("An ID proof document is required.");
        }
        try {
            StaffProfileResponse response = staffProfileService.storeIdProof(
                    id, file.getOriginalFilename(), file.getContentType(), file.getBytes());
            auditService.record(AuditActions.STAFF_ID_PROOF_UPLOADED, AuditActions.ENTITY_USER,
                    String.valueOf(id), "Uploaded ID proof for " + response.username());
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded ID proof", e);
        }
    }

    /** Views/downloads the stored ID proof document inline. */
    @GetMapping("/{id}/id-proof")
    public ResponseEntity<Resource> idProof(@PathVariable Long id) {
        return streamObject(staffProfileService.loadIdProof(id));
    }

    /** Uploads (or replaces) the staff member's profile photo (image only; compressed before storage). */
    @PostMapping(path = "/{id}/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StaffProfileResponse uploadProfileImage(@PathVariable Long id,
                                                   @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("A profile image is required.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new ValidationException("The profile photo must be an image (JPG or PNG).");
        }
        try {
            StaffProfileResponse response = staffProfileService.storeProfileImage(
                    id, file.getOriginalFilename(), contentType, file.getBytes());
            auditService.record(AuditActions.STAFF_PROFILE_IMAGE_UPLOADED, AuditActions.ENTITY_USER,
                    String.valueOf(id), "Uploaded profile photo for " + response.username());
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded profile image", e);
        }
    }

    /** Views the stored profile photo inline. */
    @GetMapping("/{id}/profile-image")
    public ResponseEntity<Resource> profileImage(@PathVariable Long id) {
        return streamObject(staffProfileService.loadProfileImage(id));
    }

    // --- Self-service profile change requests (admin approval queue) --------

    /** Lists staff profile change requests awaiting admin approval (oldest first). */
    @GetMapping("/change-requests")
    public List<ProfileChangeRequestResponse> pendingChangeRequests() {
        return changeRequestService.listPending();
    }

    /** Approves (applies) or rejects a staff profile change request. */
    @PostMapping("/change-requests/{requestId}/review")
    public ProfileChangeRequestResponse reviewChangeRequest(
            @PathVariable Long requestId,
            @Valid @RequestBody ReviewProfileChangeRequest request) {
        ProfileChangeRequestService.ReviewResult result =
                changeRequestService.review(requestId, request.status(), request.note());
        ProfileChangeRequestResponse response = result.request();
        auditService.record(
                result.applied() ? AuditActions.STAFF_PROFILE_CHANGE_APPROVED
                        : AuditActions.STAFF_PROFILE_CHANGE_REJECTED,
                AuditActions.ENTITY_USER, String.valueOf(response.userId()),
                (result.applied() ? "Approved" : "Rejected") + " profile changes for " + response.username());
        return response;
    }

    /** Streams a stored object inline with its content type. */
    private ResponseEntity<Resource> streamObject(StorageService.StoredObject object) {
        MediaType mediaType = object.contentType() != null
                ? MediaType.parseMediaType(object.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + object.filename() + "\"")
                .body(new ByteArrayResource(object.content()));
    }

    /** Records a verify/reject decision for the staff member's ID proof. */
    @PostMapping("/{id}/verify")
    public StaffProfileResponse verify(@PathVariable Long id,
                                       @Valid @RequestBody VerifyStaffRequest request) {
        StaffProfileResponse response = staffProfileService.setVerification(id, request);
        boolean verified = response.verificationStatus() == VerificationStatus.VERIFIED;
        auditService.record(
                verified ? AuditActions.STAFF_VERIFIED : AuditActions.STAFF_VERIFICATION_REJECTED,
                AuditActions.ENTITY_USER, String.valueOf(id),
                (verified ? "Verified" : "Rejected") + " ID proof for " + response.username());
        return response;
    }
}
