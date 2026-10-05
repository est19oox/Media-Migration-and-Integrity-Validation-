package mmiv.user_access_service.repository;

import mmiv.user_access_service.model.User;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class InMemoryUserRepository implements UserRepository {

    private final Map<String, User> users = new HashMap<>();

    public void save(User user) {
        users.put(
                user.getUserId(),
                user);
    }

    @Override
    public Optional<User> findById(String userId) {
        return Optional.ofNullable(
                users.get(userId));
    }
}