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
  private final long authVersion;

  public User(String userId, List<DestinationAccess> destinationAccesses) {

    this.userId = userId;
    this.destinationAccesses =
        destinationAccesses == null ? List.of() : List.copyOf(destinationAccesses);
    this.username = null;
    this.role = null;
    this.active = true;
    this.permissions = Set.of();
    this.passwordHash = null;
    this.authVersion = 0;
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
    this(userId, destinationAccesses, username, role, active, permissions, passwordHash, 0);
  }

  public User(
      String userId,
      List<DestinationAccess> destinationAccesses,
      String username,
      String role,
      boolean active,
      Set<Permission> permissions,
      String passwordHash,
      long authVersion) {
    this.authVersion = authVersion;
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

  public long getAuthVersion() {
    return authVersion;
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

  public record AccessDecision(boolean authorised, String reason) {}

  /** Destination grants belong to this account, not to JWT claims. */
  public AccessDecision accessTo(String destination, String requestedAction) {
    if (!active) return new AccessDecision(false, "User account is inactive");
    if (isAdministrator()
        && ("READ".equalsIgnoreCase(requestedAction) || "WRITE".equalsIgnoreCase(requestedAction)))
      return new AccessDecision(true, "Access granted");
    var grant =
        destinationAccesses.stream()
            .filter(a -> a.getDestination().equals(destination))
            .findFirst();
    if (grant.isEmpty()) return new AccessDecision(false, "No access to destination");
    Action action;
    try {
      action = Action.valueOf(requestedAction.toUpperCase());
    } catch (IllegalArgumentException error) {
      return new AccessDecision(false, "Invalid action");
    }
    return grant.get().getPermission().allows(action)
        ? new AccessDecision(true, "Access granted")
        : new AccessDecision(false, "Insufficient permission");
  }

  public boolean isAdministrator() {
    return active && "ADMIN".equals(role);
  }

  public boolean isVerificationAccount() {
    return username != null && username.matches("mmiv_ci_[0-9a-f]{32}");
  }

  public boolean canReceiveInvitation() {
    return active && "USER".equals(role) && !isVerificationAccount();
  }

  public boolean canBecomeAdministrator() {
    return active && "USER".equals(role);
  }

  public boolean canAcceptInvitation() {
    return active && !isVerificationAccount();
  }

  public boolean canBeManagedBy(User actor, boolean requireActive) {
    return actor != null
        && actor.isAdministrator()
        && (!requireActive || active)
        && (userId.equals(actor.userId) || "USER".equals(role));
  }

  public boolean isOtherAdministrator(String actorId) {
    return "ADMIN".equals(role) && !userId.equals(actorId);
  }

  public boolean canAuthenticate() {
    return active && passwordHash != null && !passwordHash.isBlank();
  }

  public static boolean validSignup(String username, String password) {
    return username != null && username.matches("[A-Za-z0-9_.-]{3,40}") && validPassword(password);
  }

  public static boolean validPassword(String password) {
    return password != null
        && !password.isBlank()
        && password.length() >= 8
        && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
  }

  public User deactivate() {
    return changed(role, false, passwordHash, authVersion + 1, destinationAccesses);
  }

  public User resetPassword(String encoded) {
    if (!active) throw new IllegalStateException("Account is inactive");
    return changed(
        role,
        true,
        java.util.Objects.requireNonNull(encoded),
        authVersion + 1,
        destinationAccesses);
  }

  public User acceptAdministratorRole() {
    if (!canBecomeAdministrator())
      throw new IllegalStateException("Invitation requires an active ordinary user");
    return changed("ADMIN", true, passwordHash, authVersion, destinationAccesses);
  }

  public User grant(String destination, Permission permission) {
    if (destination == null || destination.isBlank() || permission == null)
      throw new IllegalArgumentException("Destination and permission are required");
    var grants = new java.util.ArrayList<>(destinationAccesses);
    grants.removeIf(a -> destination.equals(a.getDestination()));
    grants.add(new DestinationAccess(destination, permission));
    return changed(role, active, passwordHash, authVersion, grants);
  }

  public User revoke(String destination) {
    if (destination == null || destination.isBlank())
      throw new IllegalArgumentException("Destination is required");
    return changed(
        role,
        active,
        passwordHash,
        authVersion,
        destinationAccesses.stream().filter(a -> !destination.equals(a.getDestination())).toList());
  }

  private User changed(
      String newRole, boolean enabled, String hash, long version, List<DestinationAccess> grants) {
    return new User(userId, grants, username, newRole, enabled, permissions, hash, version);
  }
}
