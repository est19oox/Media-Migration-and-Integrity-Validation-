package mmiv.user_access_service.repository;

import java.util.Optional;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;

public interface UserRepository {

  Optional<User> findById(String userId);

  void save(User user);

  void create(User user);

  boolean deleteVerificationAccount(String userId);

  Optional<User> findByUsername(String username);

  java.util.List<User> searchUsers(String query);

  void grantDestinationAccess(String userId, String destination, Permission permission);

  void createAdminInvitation(String userId, String tokenHash, java.time.Instant expiresAt, String issuerId);

  boolean acceptAdminInvitation(String userId, String tokenHash, java.time.Instant now);

  void revokeDestinationAccess(String userId, String destination);
}
