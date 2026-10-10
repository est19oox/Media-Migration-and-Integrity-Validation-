package mmiv.user_access_service.repository;

import mmiv.user_access_service.model.User;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Repository
@Profile("test")
public class InMemoryUserRepository implements UserRepository {

    private final Map<String, User> users = new HashMap<>();
    
    @Override 
    public void save(User user) {
        User existing = users.get(user.getUserId());
        if (existing == null) {
            users.put(user.getUserId(), user);
            return;
        }

        users.put(user.getUserId(), new User(
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
    public Optional<User> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }

        return users.values().stream()
                .filter(user -> username.equals(user.getUsername()))
                .findFirst();
    }

    @Override
    public void grantDestinationAccess(
            String userId,
            String destination,
            Permission permission) {
        User user = users.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("Unknown user: " + userId);
        }
        if (destination == null || destination.isBlank() || permission == null) {
            throw new IllegalArgumentException(
                    "Destination and permission are required");
        }

        java.util.List<DestinationAccess> accesses =
                new java.util.ArrayList<>(user.getDestinationAccesses());
        accesses.removeIf(access -> destination.equals(access.getDestination()));
        accesses.add(new DestinationAccess(destination, permission));
        users.put(userId, new User(
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
        users.put(userId, new User(
                userId,
                accesses,
                user.getUsername(),
                user.getRole(),
                user.isActive(),
                user.getPermissions(),
                user.getPasswordHash()));
    }
}