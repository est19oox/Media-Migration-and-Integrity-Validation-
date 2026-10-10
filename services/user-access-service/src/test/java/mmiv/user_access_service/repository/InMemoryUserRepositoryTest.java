package mmiv.user_access_service.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import org.junit.jupiter.api.Test;

class InMemoryUserRepositoryTest {

  @Test
  void shouldSaveAndFindUserById() {
    User alice = new User("U001", "alice", "analyst", true, Set.of(Permission.READ));
    InMemoryUserRepository repository = new InMemoryUserRepository();

    repository.save(alice);

    Optional<User> result = repository.findById("U001");

    assertTrue(result.isPresent());
    assertEquals(alice, result.get());
  }

  @Test
  void shouldFindUserByUsername() {

    InMemoryUserRepository repository = new InMemoryUserRepository();

    User user = new User("U001", "alice", "OPERATOR", true, Set.of(Permission.READ));

    repository.save(user);

    Optional<User> result = repository.findByUsername("alice");

    assertTrue(result.isPresent());
    assertEquals("U001", result.get().getUserId());
  }

  @Test
  void shouldReturnEmptyForUnknownUsername() {

    InMemoryUserRepository repository = new InMemoryUserRepository();

    Optional<User> result = repository.findByUsername("unknown");

    assertTrue(result.isEmpty());
  }
}
