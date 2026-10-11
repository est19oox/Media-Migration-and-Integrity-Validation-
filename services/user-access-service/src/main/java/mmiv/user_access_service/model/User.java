package mmiv.user_access_service.model;

import java.util.List;
import java.util.Set;

public class User {

  private final String userId;
  private final List<DestinationAccess> destinationAccesses;
  private final String username;
  private final String role;
  private final boolean active;
  private final Set<Permission> permissions;
  private final String passwordHash;

  public User(String userId, List<DestinationAccess> destinationAccesses) {

    this.userId = userId;
    this.destinationAccesses =
        destinationAccesses == null ? List.of() : List.copyOf(destinationAccesses);
    this.username = null;
    this.role = null;
    this.active = true;
    this.permissions = Set.of();
    this.passwordHash = null;
  }

  public User(
      String userId, String username, String role, boolean active, Set<Permission> permissions) {

    this(userId, username, role, active, permissions, null);
  }

  public User(
      String userId,
      String username,
      String role,
      boolean active,
      Set<Permission> permissions,
      String passwordHash) {
    this(userId, List.of(), username, role, active, permissions, passwordHash);
  }

  public User(
      String userId,
      List<DestinationAccess> destinationAccesses,
      String username,
      String role,
      boolean active,
      Set<Permission> permissions,
      String passwordHash) {

    this.userId = userId;
    this.username = username;
    this.role = role;
    this.active = active;
    this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    this.passwordHash = passwordHash;
    this.destinationAccesses =
        destinationAccesses == null ? List.of() : List.copyOf(destinationAccesses);
  }

  public String getUserId() {
    return userId;
  }

  public List<DestinationAccess> getDestinationAccesses() {
    return destinationAccesses;
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

  public Set<Permission> getPermissions() {
    return permissions;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  /**
   * Returns the user's identity-level permission for authentication and token claims. Destination
   * authorization must use {@link #getDestinationAccesses()} instead.
   */
  public boolean hasPermission(Permission permission) {
    return active && permissions.contains(permission);
  }
}
