package mmiv.user_access_service.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import org.junit.jupiter.api.Test;

class InMemoryUserRepositoryTest {

  @Test
  void shouldFindExistingUserById() {

    User alice =
        new User("U001", List.of(new DestinationAccess("mmiv-bucket-a", Permission.WRITE)));

    InMemoryUserRepository repository = new InMemoryUserRepository();

    repository.save(alice);

    Optional<User> result = repository.findById("U001");

    assertTrue(result.isPresent());
    assertEquals("U001", result.get().getUserId());
  }

  @Test
  void shouldFindExistingUserByUsername() {
    User alice =
        new User(
            "U001", "alice", "OPERATOR", true, java.util.Set.of(Permission.READ), "bcrypt-hash");

    InMemoryUserRepository repository = new InMemoryUserRepository();
    repository.save(alice);

    Optional<User> result = repository.findByUsername("alice");

    assertTrue(result.isPresent());
    assertEquals("U001", result.get().getUserId());
    assertEquals("bcrypt-hash", result.get().getPasswordHash());
  }

  @Test
  void shouldPreserveDestinationAccessWhenSavingIdentityChanges() {
    InMemoryUserRepository repository = new InMemoryUserRepository();
    repository.save(
        new User("U001", List.of(new DestinationAccess("mmiv-bucket-a", Permission.WRITE))));

    repository.save(
        new User(
            "U001", "alice", "OPERATOR", true, java.util.Set.of(Permission.READ), "bcrypt-hash"));

    User result = repository.findById("U001").orElseThrow();

    assertEquals(1, result.getDestinationAccesses().size());
    assertEquals(Permission.WRITE, result.getDestinationAccesses().get(0).getPermission());
  }

  @Test
  void shouldGrantAndRevokeOnlyExplicitDestinationAccess() {
    InMemoryUserRepository repository = new InMemoryUserRepository();
    repository.save(
        new User(
            "U001", "alice", "OPERATOR", true, java.util.Set.of(Permission.READ), "bcrypt-hash"));

    repository.grantDestinationAccess("U001", "mmiv-bucket-a", Permission.WRITE);
    repository.grantDestinationAccess("U001", "mmiv-bucket-b", Permission.READ);
    repository.grantDestinationAccess("U001", "mmiv-bucket-a", Permission.READ);

    User granted = repository.findById("U001").orElseThrow();
    assertEquals(2, granted.getDestinationAccesses().size());
    assertEquals(
        Permission.READ,
        granted.getDestinationAccesses().stream()
            .filter(access -> access.getDestination().equals("mmiv-bucket-a"))
            .findFirst()
            .orElseThrow()
            .getPermission());

    repository.revokeDestinationAccess("U001", "mmiv-bucket-a");

    User revoked = repository.findById("U001").orElseThrow();
    assertEquals(1, revoked.getDestinationAccesses().size());
    assertFalse(
        revoked.getDestinationAccesses().stream()
            .anyMatch(access -> access.getDestination().equals("mmiv-bucket-a")));
    assertTrue(
        revoked.getDestinationAccesses().stream()
            .anyMatch(access -> access.getDestination().equals("mmiv-bucket-b")));
  }
}
