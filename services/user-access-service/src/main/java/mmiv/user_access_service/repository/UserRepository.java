package mmiv.user_access_service.repository;

import mmiv.user_access_service.model.User;

import java.util.Optional;

public interface UserRepository {

    Optional<User> findById(String userId);

    void save(User user);
}
