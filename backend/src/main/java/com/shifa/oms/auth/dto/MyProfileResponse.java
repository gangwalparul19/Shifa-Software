package com.shifa.oms.auth.dto;

/**
 * The "My Profile" payload for a signed-in staff member: their current profile
 * plus their pending change request (if one is awaiting admin approval).
 */
public record MyProfileResponse(
        StaffProfileResponse profile,
        ProfileChangeRequestResponse pending) {
}
