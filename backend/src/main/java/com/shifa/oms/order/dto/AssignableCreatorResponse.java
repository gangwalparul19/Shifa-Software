package com.shifa.oms.order.dto;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;

/**
 * One user an ADMIN may place a New Order on behalf of, from
 * {@code GET /api/orders/assignable-creators}: active salespeople and team leads.
 * The order's {@code created_by} is set to the chosen {@code id}, so it appears
 * in that user's (and their team lead's) scoped lists and counts toward their
 * performance.
 *
 * @param id   the user id to send as {@code onBehalfOfUserId}
 * @param name display name (full name, else username)
 * @param role SALESPERSON or TEAM_LEAD (for grouping/labelling in the dropdown)
 */
public record AssignableCreatorResponse(Long id, String name, String role) {

    public static AssignableCreatorResponse from(User user) {
        String name = (user.getFullName() != null && !user.getFullName().isBlank())
                ? user.getFullName() : user.getUsername();
        Role role = user.getRole();
        return new AssignableCreatorResponse(user.getId(), name, role == null ? null : role.name());
    }
}
