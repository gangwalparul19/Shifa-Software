package com.shifa.oms.auth.dto;

import com.shifa.oms.auth.User;

/**
 * A team lead for the admin team-management picker: identity, active flag, and
 * how many salespeople are currently assigned to them.
 */
public record TeamLeadSummary(Long id, String fullName, String username, boolean active, int memberCount) {

    public static TeamLeadSummary from(User user, int memberCount) {
        return new TeamLeadSummary(user.getId(), user.getFullName(), user.getUsername(),
                user.isActive(), memberCount);
    }
}
