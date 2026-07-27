package com.shifa.oms.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A platform user, mapped to the existing {@code users} table (see the V1 Flyway
 * migration). Passwords are stored as BCrypt hashes in {@link #passwordHash};
 * the plaintext password is never persisted.
 *
 * <p>The {@link Role} is stored as its {@code VARCHAR} name (Req 5.1). The
 * {@link #active} flag lets an admin disable a login without deleting history.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    /** Optional customer email (nullable; staff rows leave this null). */
    @Column(name = "email", length = 150)
    private String email;

    /** Optional 10-digit customer mobile (nullable; staff rows leave this null). */
    @Column(name = "mobile", length = 10)
    private String mobile;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * The team lead this user (a salesperson) reports to, or null when
     * unassigned. Self-referencing to another {@code users.id} whose role is
     * {@link Role#TEAM_LEAD}. Drives team-scoped visibility (V43).
     */
    @Column(name = "team_lead_id")
    private Long teamLeadId;

    // --- Staff onboarding profile (V31, nullable; customers leave these null) ---

    /** Staff date of birth (optional). */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** Staff residential/postal address (optional). */
    @Column(name = "address", length = 500)
    private String address;

    /** Date the staff member joined (optional). */
    @Column(name = "joined_on")
    private LocalDate joinedOn;

    /** Type of the uploaded ID document (optional until captured). */
    @Enumerated(EnumType.STRING)
    @Column(name = "id_proof_type", length = 30)
    private IdProofType idProofType;

    /** The ID document number as entered by the admin (optional). */
    @Column(name = "id_proof_number", length = 60)
    private String idProofNumber;

    /** Opaque storage key of the uploaded ID document (resolved via StorageService). */
    @Column(name = "id_proof_key", length = 255)
    private String idProofKey;

    /** Opaque storage key of the uploaded profile photo (resolved via StorageService). */
    @Column(name = "profile_image_key", length = 255)
    private String profileImageKey;

    /** Identity-verification state; new staff default to PENDING. */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private VerificationStatus verificationStatus = VerificationStatus.PENDING;

    /** Optional note captured when verifying/rejecting the ID proof. */
    @Column(name = "verification_note", length = 500)
    private String verificationNote;

    /** When the verification decision was recorded (optional). */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** The admin user id who recorded the verification decision (optional). */
    @Column(name = "verified_by")
    private Long verifiedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected User() {
        // Required by JPA.
    }

    /** Populates the creation timestamp before the first insert so the DB never receives a null. */
    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public User(String username, String passwordHash, Role role, String fullName, boolean active) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.fullName = fullName;
        this.active = active;
    }

    /** Full constructor including the optional customer identity fields. */
    public User(String username, String passwordHash, Role role, String fullName,
                String email, String mobile, boolean active) {
        this(username, passwordHash, role, fullName, active);
        this.email = email;
        this.mobile = mobile;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getTeamLeadId() {
        return teamLeadId;
    }

    public void setTeamLeadId(Long teamLeadId) {
        this.teamLeadId = teamLeadId;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public LocalDate getJoinedOn() {
        return joinedOn;
    }

    public void setJoinedOn(LocalDate joinedOn) {
        this.joinedOn = joinedOn;
    }

    public IdProofType getIdProofType() {
        return idProofType;
    }

    public void setIdProofType(IdProofType idProofType) {
        this.idProofType = idProofType;
    }

    public String getIdProofNumber() {
        return idProofNumber;
    }

    public void setIdProofNumber(String idProofNumber) {
        this.idProofNumber = idProofNumber;
    }

    public String getIdProofKey() {
        return idProofKey;
    }

    public void setIdProofKey(String idProofKey) {
        this.idProofKey = idProofKey;
    }

    public String getProfileImageKey() {
        return profileImageKey;
    }

    public void setProfileImageKey(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public String getVerificationNote() {
        return verificationNote;
    }

    public void setVerificationNote(String verificationNote) {
        this.verificationNote = verificationNote;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public Long getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(Long verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
