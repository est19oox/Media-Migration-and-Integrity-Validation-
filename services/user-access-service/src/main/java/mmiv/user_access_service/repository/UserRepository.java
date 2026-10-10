package mmiv.user_access_service.repository;

import mmiv.user_access_service.model.User;
import mmiv.user_access_service.model.Permission;

import java.util.Optional;

public interface UserRepository {

    Optional<User> findById(String userId);

    void save(User user);

    Optional<User> findByUsername(String username);

    void grantDestinationAccess(
            String userId,
            String destination,
            Permission permission);

    void revokeDestinationAccess(String userId, String destination);
}
