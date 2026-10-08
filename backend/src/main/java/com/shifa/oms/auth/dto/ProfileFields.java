package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.IdProofType;
import com.shifa.oms.auth.ProfileChangeRequest;
import com.shifa.oms.auth.User;

import java.time.LocalDate;

/**
 * The editable profile fields, used to show a change request's <em>proposed</em>
 * values alongside the staff member's <em>current</em> values (for an at-a-glance
 * admin diff).
 */
public record ProfileFields(
        String fullName,
        String email,
        String mobile,
        LocalDate dateOfBirth,
        String address,
        IdProofType idProofType,
        String idProofNumber) {

    /** The current values held on the user. */
    public static ProfileFields ofUser(User user) {
        return new ProfileFields(
                user.getFullName(),
                user.getEmail(),
                user.getMobile(),
                user.getDateOfBirth(),
                user.getAddress(),
                user.getIdProofType(),
                user.getIdProofNumber());
    }

    /** The proposed values held on a change request. */
    public static ProfileFields ofRequest(ProfileChangeRequest request) {
        return new ProfileFields(
                request.getFullName(),
                request.getEmail(),
                request.getMobile(),
                request.getDateOfBirth(),
                request.getAddress(),
                request.getIdProofType(),
                request.getIdProofNumber());
    }
}
