package mmiv.user_access_service.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class User {

  private final String userId;
  private final String username;
  private final String role;
  private final boolean active;
  private final Set<Permission> permissions;
  private final String passwordHash;

  // Existing constructor for backward compatibility
  public User(
      String userId, String username, String role, boolean active, Set<Permission> permissions) {

    this(userId, username, role, active, permissions, null);
  }

  // Constructor for authenticated users
  public User(
      String userId,
      String username,
      String role,
      boolean active,
      Set<Permission> permissions,
      String passwordHash) {

    this.userId = userId;
    this.username = username;
    this.role = role;
    this.active = active;
    this.passwordHash = passwordHash;
    this.permissions =
        permissions == null
            ? Collections.emptySet()
            : Collections.unmodifiableSet(new HashSet<>(permissions));
  }

  public String getUserId() {
    return userId;
  }

  public String getUsername() {
    return username;
  }

  public String getRole() {
    return role;
  }

  public boolean isActive() {
    return active;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Set<Permission> getPermissions() {
    return permissions;
  }

  public boolean hasPermission(Permission permission) {
    return active && permissions.contains(permission);
  }
}
