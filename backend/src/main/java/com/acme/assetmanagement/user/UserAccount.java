package com.acme.assetmanagement.user;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

@Entity
@Table(name = "user_accounts")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String username;

    @Column(nullable = false, length = 80)
    private String displayName;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "dingtalk_identity", unique = true, length = 64)
    private String dingtalkIdentity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 40)
    private Set<Permission> permissions = EnumSet.noneOf(Permission.class);

    @Column(nullable = false)
    private boolean enabled = true;

    // Null identifies pre-permission-management accounts. Preserve their former full access.
    private Boolean accessConfigured;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DataScope dataScope;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_departments", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "department", length = 120)
    private Set<String> departments = new java.util.HashSet<>();

    @Lob
    @Column(name = "avatar_data", columnDefinition = "LONGBLOB")
    private byte[] avatarData;

    @Column(name = "avatar_content_type", length = 40)
    private String avatarContentType;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void createTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDingtalkIdentity() { return dingtalkIdentity; }
    public void setDingtalkIdentity(String identity) { this.dingtalkIdentity = identity; }
    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }
    public Set<Permission> getPermissions() { return permissions; }
    public void setPermissions(Set<Permission> permissions) {
        this.permissions = permissions == null || permissions.isEmpty()
                ? EnumSet.noneOf(Permission.class)
                : EnumSet.copyOf(permissions);
    }
    public boolean isEnabled() { return enabled; }
    public Set<Permission> effectivePermissions() {
        return role == UserRole.SUPER_ADMIN || !Boolean.TRUE.equals(accessConfigured)
                ? Set.of(Permission.values()) : Set.copyOf(permissions);
    }
    public void setAccessConfigured(boolean configured) { this.accessConfigured = configured; }
    public DataScope getDataScope() {
        return role == UserRole.SUPER_ADMIN || !Boolean.TRUE.equals(accessConfigured) ? DataScope.ALL
                : dataScope == null ? DataScope.SELF : dataScope;
    }
    public void setDataScope(DataScope scope) { this.dataScope = scope; }
    public Set<String> getDepartments() { return departments; }
    public void setDepartments(Set<String> departments) { this.departments = new java.util.HashSet<>(departments); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public byte[] getAvatarData() { return avatarData; }
    public void setAvatarData(byte[] avatarData) { this.avatarData = avatarData; }
    public String getAvatarContentType() { return avatarContentType; }
    public void setAvatarContentType(String avatarContentType) { this.avatarContentType = avatarContentType; }
    public boolean hasAvatar() { return avatarData != null && avatarData.length > 0; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
