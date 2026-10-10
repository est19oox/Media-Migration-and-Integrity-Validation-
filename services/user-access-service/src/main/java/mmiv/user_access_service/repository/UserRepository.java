package mmiv.user_access_service.repository;

import java.util.Optional;
import mmiv.user_access_service.model.User;

public interface UserRepository {

  Optional<User> findById(String userId);

  void save(User user);

  Optional<User> findByUsername(String username);
}
