package com.shifa.oms.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A configurable CRM permission grant (CRM expansion Phase 3, Req 19; design
 * &sect;3.4), mapped to the {@code crm_permissions} table (migration V92).
 *
 * <p>A grant applies either to a {@link #role} (all users of that role) or to a
 * single {@link #userId}; {@link #granted} may be {@code false} to explicitly
 * revoke. {@link PermissionService} resolves a user grant ahead of a role grant,
 * and falls back to a safe default (reproducing today's behaviour) when no row
 * matches.
 */
@Entity
@Table(name = "crm_permissions")
public class CrmPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permission", nullable = false, length = 40)
    private String permission;

    @Column(name = "role", length = 20)
    private String role;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "granted", nullable = false)
    private boolean granted = true;

    protected CrmPermission() {
        // Required by JPA.
    }

    public CrmPermission(String permission, String role, Long userId, boolean granted) {
        this.permission = permission;
        this.role = role;
        this.userId = userId;
        this.granted = granted;
    }

    public Long getId() {
        return id;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public boolean isGranted() {
        return granted;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
    }
}
