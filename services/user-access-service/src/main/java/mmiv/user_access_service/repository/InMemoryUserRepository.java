package mmiv.user_access_service.repository;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Optional;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryUserRepository implements UserRepository {

  private record Invitation(String userId, java.time.Instant expiresAt) {}
  private final Map<String, Invitation> invitations = new ConcurrentHashMap<>();

  @Override
  public synchronized void createAdminInvitation(String userId, String tokenHash, java.time.Instant expiresAt, String issuerId) {
    if (invitations.putIfAbsent(tokenHash, new Invitation(userId, expiresAt)) != null)
      throw new org.springframework.dao.DuplicateKeyException("Invitation already exists");
  }

  @Override
  public synchronized boolean acceptAdminInvitation(String userId, String tokenHash, java.time.Instant now) {
    var invitation = invitations.get(tokenHash);
    var user = users.get(userId);
    if (invitation == null || !invitation.userId().equals(userId) || !invitation.expiresAt().isAfter(now)
        || user == null || !user.isActive() || !"USER".equals(user.getRole())) return false;
    invitations.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));
    users.put(userId, new User(userId, user.getDestinationAccesses(), user.getUsername(), "ADMIN",
        true, user.getPermissions(), user.getPasswordHash()));
    return true;
  }

  private final Map<String, User> users = new ConcurrentHashMap<>();

  public java.util.List<User> searchUsers(String query) {
    return users.values().stream().filter(user -> user.getUsername().toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT)))
        .sorted(java.util.Comparator.comparing(User::getUsername)).limit(100).toList();
  }

  @Override
  public synchronized void create(User user) {
    if (users.containsKey(user.getUserId()) || findByUsername(user.getUsername()).isPresent()) {
      throw new org.springframework.dao.DuplicateKeyException("User already exists");
    }
    users.put(user.getUserId(), user);
  }

  @Override
  public void save(User user) {
    User existing = users.get(user.getUserId());
    if (existing == null) {
      users.put(user.getUserId(), user);
      return;
    }

    users.put(
        user.getUserId(),
        new User(
            user.getUserId(),
            existing.getDestinationAccesses(),
            user.getUsername(),
            user.getRole(),
            user.isActive(),
            user.getPermissions(),
            user.getPasswordHash()));
  }

  @Override
  public Optional<User> findById(String userId) {
    return Optional.ofNullable(users.get(userId));
  }

  @Override
  public boolean deleteVerificationAccount(String userId) {
    User user = users.get(userId);
    return user != null && "USER".equals(user.getRole())
        && user.getUsername() != null && user.getUsername().matches("mmiv_ci_[0-9a-f]{32}")
        && users.remove(userId, user);
  }

  @Override
  public Optional<User> findByUsername(String username) {
    if (username == null) {
      return Optional.empty();
    }

    return users.values().stream().filter(user -> username.equals(user.getUsername())).findFirst();
  }

  @Override
  public void grantDestinationAccess(String userId, String destination, Permission permission) {
    User user = users.get(userId);
    if (user == null) {
      throw new IllegalArgumentException("Unknown user: " + userId);
    }
    if (destination == null || destination.isBlank() || permission == null) {
      throw new IllegalArgumentException("Destination and permission are required");
    }

    java.util.List<DestinationAccess> accesses =
        new java.util.ArrayList<>(user.getDestinationAccesses());
    accesses.removeIf(access -> destination.equals(access.getDestination()));
    accesses.add(new DestinationAccess(destination, permission));
    users.put(
        userId,
        new User(
            userId,
            accesses,
            user.getUsername(),
            user.getRole(),
            user.isActive(),
            user.getPermissions(),
            user.getPasswordHash()));
  }

  @Override
  public void revokeDestinationAccess(String userId, String destination) {
    User user = users.get(userId);
    if (user == null) {
      throw new IllegalArgumentException("Unknown user: " + userId);
    }
    if (destination == null || destination.isBlank()) {
      throw new IllegalArgumentException("Destination is required");
    }

    java.util.List<DestinationAccess> accesses =
        new java.util.ArrayList<>(user.getDestinationAccesses());
    accesses.removeIf(access -> destination.equals(access.getDestination()));
    users.put(
        userId,
        new User(
            userId,
            accesses,
            user.getUsername(),
            user.getRole(),
            user.isActive(),
            user.getPermissions(),
            user.getPasswordHash()));
  }
}
