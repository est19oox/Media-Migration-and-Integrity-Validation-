package mmiv.user_access_service.repository;

import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryUserRepositoryTest {

    @Test
    void shouldSaveAndFindUserById() {
        User alice = new User(
                "U001", "alice", "analyst", true, Set.of(Permission.READ));
        InMemoryUserRepository repository = new InMemoryUserRepository();

        repository.save(alice);

        Optional<User> result = repository.findById("U001");

        assertTrue(result.isPresent());
        assertEquals(alice, result.get());
    }
}
