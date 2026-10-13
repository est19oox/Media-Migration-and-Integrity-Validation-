package mmiv.user_access_service.repository;

import java.util.Optional;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;

public interface UserRepository {

  Optional<User> findById(String userId);

  void save(User user);

  void create(User user);

  boolean deleteVerificationAccount(String userId);

  /** Soft delete: retain account and permissions, deactivate login and revoke sessions/links. */
  boolean deleteAccount(String actorId, String targetId);

  boolean createPasswordReset(
      String actorId, String targetId, String tokenHash, java.time.Instant expiresAt);

  java.util.Optional<String> consumePasswordReset(
      String tokenHash, java.util.function.Supplier<String> passwordHash, java.time.Instant now);

  Optional<User> findByUsername(String username);

  java.util.List<User> searchUsers(String query);

  void grantDestinationAccess(String userId, String destination, Permission permission);

  void createAdminInvitation(
      String userId, String tokenHash, java.time.Instant expiresAt, String issuerId);

  boolean acceptAdminInvitation(String userId, String tokenHash, java.time.Instant now);

  void revokeDestinationAccess(String userId, String destination);
}
