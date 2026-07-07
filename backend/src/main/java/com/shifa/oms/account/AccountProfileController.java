package com.shifa.oms.account;

import com.shifa.oms.account.dto.ProfileResponse;
import com.shifa.oms.account.dto.ProfileUpdateRequest;
import com.shifa.oms.auth.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer profile endpoints ({@code /api/account/profile}). A {@code CUSTOMER}
 * can view and update their own name/email/mobile only; role and username are
 * immutable here.
 */
@RestController
@RequestMapping("/api/account/profile")
@PreAuthorize("hasRole('CUSTOMER')")
public class AccountProfileController {

    private final ProfileService profileService;
    private final CurrentUserService currentUserService;

    public AccountProfileController(ProfileService profileService, CurrentUserService currentUserService) {
        this.profileService = profileService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ProfileResponse profile() {
        return profileService.getProfile(currentUserId());
    }

    @PutMapping
    public ProfileResponse update(@Valid @RequestBody ProfileUpdateRequest request) {
        return profileService.updateProfile(currentUserId(), request);
    }

    private Long currentUserId() {
        return currentUserService.requireCurrentUser().userId();
    }
}
