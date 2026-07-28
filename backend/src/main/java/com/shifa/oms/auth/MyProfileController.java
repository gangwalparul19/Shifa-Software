package com.shifa.oms.auth;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.dto.MyProfileResponse;
import com.shifa.oms.auth.dto.ProfileChangeRequestResponse;
import com.shifa.oms.auth.dto.SubmitProfileChangeRequest;
import com.shifa.oms.platform.storage.StorageService;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service "My Profile" for the signed-in staff member ({@code /api/me/profile}).
 *
 * <p>Open to any authenticated staff role (not customers). A staff member can
 * view their own profile and submit a change request, but the request only
 * <em>queues</em> the edit for admin approval — nothing on their account changes
 * until an admin approves it (see {@link ProfileChangeRequestService}).
 */
@RestController
@RequestMapping("/api/me/profile")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','SALESPERSON','PACKING_USER','TEAM_LEAD','PAYMENT_VERIFIER')")
public class MyProfileController {

    private final ProfileChangeRequestService changeRequestService;
    private final StaffProfileService staffProfileService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public MyProfileController(ProfileChangeRequestService changeRequestService,
                               StaffProfileService staffProfileService,
                               CurrentUserService currentUserService,
                               AuditService auditService) {
        this.changeRequestService = changeRequestService;
        this.staffProfileService = staffProfileService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    /** The signed-in staff member's current profile + any pending change request. */
    @GetMapping
    public MyProfileResponse myProfile() {
        return changeRequestService.getMyProfile();
    }

    /** Submits (or replaces) the signed-in staff member's pending change request. */
    @PostMapping("/change-request")
    public ProfileChangeRequestResponse submit(@Valid @RequestBody SubmitProfileChangeRequest request) {
        ProfileChangeRequestResponse response = changeRequestService.submitMyChangeRequest(request);
        auditService.record(AuditActions.STAFF_PROFILE_CHANGE_REQUESTED, AuditActions.ENTITY_USER,
                String.valueOf(response.userId()), "Requested profile changes (awaiting approval)");
        return response;
    }

    /** Views the signed-in staff member's own profile photo inline. */
    @GetMapping("/photo")
    public ResponseEntity<Resource> myPhoto() {
        Long myId = currentUserService.requireCurrentUser().userId();
        StorageService.StoredObject object = staffProfileService.loadProfileImage(myId);
        MediaType mediaType = object.contentType() != null
                ? MediaType.parseMediaType(object.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + object.filename() + "\"")
                .body(new ByteArrayResource(object.content()));
    }
}
