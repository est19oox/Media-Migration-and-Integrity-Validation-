package mmiv.user_access_service.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.Set;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
      "spring.datasource.username=USER_ACCESS"
    })
class OracleUserRepositoryIT {

  private static final String TEST_USER_ID = "IT_U001";

  @Autowired private OracleUserRepository repository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM user_permissions WHERE user_id = ?", TEST_USER_ID);

    jdbcTemplate.update("DELETE FROM app_users WHERE user_id = ?", TEST_USER_ID);
  }

  @Test
  void shouldSaveAndFindUser() {
    User user =
        new User(
            TEST_USER_ID,
            "integration-alice",
            "OPERATOR",
            true,
            Set.of(Permission.READ, Permission.WRITE));

    repository.save(user);

    Optional<User> result = repository.findById(TEST_USER_ID);

    assertTrue(result.isPresent());

    User saved = result.orElseThrow();

    assertEquals(TEST_USER_ID, saved.getUserId());
    assertEquals("integration-alice", saved.getUsername());
    assertEquals("OPERATOR", saved.getRole());
    assertTrue(saved.isActive());
    assertEquals(Set.of(Permission.READ, Permission.WRITE), saved.getPermissions());
  }

  @Test
  void shouldUpdateExistingUserAndReplacePermissions() {
    User original =
        new User(
            TEST_USER_ID,
            "integration-alice",
            "OPERATOR",
            true,
            Set.of(Permission.READ, Permission.WRITE));

    repository.save(original);

    User updated =
        new User(TEST_USER_ID, "integration-alice", "VIEWER", false, Set.of(Permission.READ));

    repository.save(updated);

    User saved = repository.findById(TEST_USER_ID).orElseThrow();

    assertEquals("VIEWER", saved.getRole());
    assertFalse(saved.isActive());
    assertEquals(Set.of(Permission.READ), saved.getPermissions());
    assertFalse(saved.getPermissions().contains(Permission.WRITE));
  }

  @Test
  void shouldFindUserByUsernameWithPasswordHash() {
    User user =
        new User(
            TEST_USER_ID,
            "integration-alice",
            "OPERATOR",
            true,
            Set.of(Permission.READ),
            "test-hash-value");

    repository.save(user);

    User loaded = repository.findByUsername("integration-alice").orElseThrow();

    assertEquals(TEST_USER_ID, loaded.getUserId());
    assertEquals("integration-alice", loaded.getUsername());
    assertEquals("test-hash-value", loaded.getPasswordHash());
    assertEquals(Set.of(Permission.READ), loaded.getPermissions());
  }
}
