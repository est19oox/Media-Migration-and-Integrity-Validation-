package mmiv.user_access_service.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.dto.AccessCheckRequest;
import mmiv.user_access_service.model.Action;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.service.AccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "jwt.issuer=https://auth.mmiv.example",
      "jwt.key-id=mmiv-integration-key"
    })
@ActiveProfiles("integration")
@Import(TestJwtConfig.class)
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
      "spring.datasource.username=USER_ACCESS"
    })
class OracleUserRepositoryIT {

  private static final String USER_ID = "IT_REPO_" + "5f2d8a7c";
  private static final String USERNAME = "it_repo_5f2d8a7c";
  private static final String PASSWORD_HASH = "$2a$12$integration-hash";

  @Autowired private OracleUserRepository repository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private AccessService accessService;

  @BeforeEach
  void createIsolatedUser() {
    cleanup();
    repository.save(user(List.of()));
  }

  @AfterEach
  void cleanupIsolatedUser() {
    cleanup();
  }

  @Test
  void savesAndLoadsUserByIdAndUsername() {
    User byId = repository.findById(USER_ID).orElseThrow();
    User byUsername = repository.findByUsername(USERNAME).orElseThrow();

    assertEquals(USER_ID, byId.getUserId());
    assertEquals(USERNAME, byUsername.getUsername());
    assertEquals(PASSWORD_HASH, byId.getPasswordHash());
    assertEquals(Set.of(Permission.READ), byId.getPermissions());
  }

  @Test
  void preservesDestinationAssignmentsAndAuthorizationSemantics() {
    repository.grantDestinationAccess(USER_ID, "destination-write", Permission.WRITE);
    repository.grantDestinationAccess(USER_ID, "destination-read", Permission.READ);

    User loaded = repository.findById(USER_ID).orElseThrow();
    assertEquals(2, loaded.getDestinationAccesses().size());
    assertEquals(
        Set.of(
            Map.entry("destination-write", Permission.WRITE),
            Map.entry("destination-read", Permission.READ)),
        destinationAssignments(loaded));

    assertTrue(authorized("destination-write", "READ"));
    assertTrue(authorized("destination-write", "WRITE"));
    assertTrue(authorized("destination-read", "READ"));
    assertFalse(authorized("destination-read", "WRITE"));
    assertFalse(authorized("destination-missing", "READ"));
  }

  @Test
  void updatesAndRevokesOnlyTheRequestedDestination() {
    repository.grantDestinationAccess(USER_ID, "destination-a", Permission.READ);
    repository.grantDestinationAccess(USER_ID, "destination-b", Permission.WRITE);
    repository.grantDestinationAccess(USER_ID, "destination-a", Permission.WRITE);

    assertTrue(authorized("destination-a", "WRITE"));
    assertTrue(authorized("destination-b", "WRITE"));

    repository.revokeDestinationAccess(USER_ID, "destination-a");

    assertFalse(authorized("destination-a", "READ"));
    assertTrue(authorized("destination-b", "READ"));
    assertTrue(authorized("destination-b", "WRITE"));
  }

  @Test
  void saveNeverRemovesDestinationAssignmentsOrGrantsGlobalAccess() {
    repository.grantDestinationAccess(USER_ID, "destination-preserved", Permission.READ);

    repository.save(
        user(List.of(new DestinationAccess("destination-unrequested", Permission.WRITE))));

    User loaded = repository.findById(USER_ID).orElseThrow();
    assertEquals(1, loaded.getDestinationAccesses().size());
    assertEquals(
        Set.of(Map.entry("destination-preserved", Permission.READ)),
        destinationAssignments(loaded));
    assertFalse(authorized("destination-unrequested", "READ"));
    assertFalse(authorized("destination-unassigned", "READ"));
  }

  private Set<Map.Entry<String, Permission>> destinationAssignments(User user) {
    return user.getDestinationAccesses().stream()
        .map(access -> Map.entry(access.getDestination(), access.getPermission()))
        .collect(Collectors.toSet());
  }

  private boolean authorized(String destination, String action) {
    AccessCheckRequest request = new AccessCheckRequest();
    request.setUserId(USER_ID);
    request.setDestination(destination);
    request.setAction(Action.valueOf(action).name());
    return accessService.checkAccess(request).isAuthorised();
  }

  private User user(List<DestinationAccess> destinationAccesses) {
    return new User(
        USER_ID,
        destinationAccesses,
        USERNAME,
        "OPERATOR",
        true,
        Set.of(Permission.READ),
        PASSWORD_HASH);
  }

  private void cleanup() {
    jdbcTemplate.update("DELETE FROM user_permissions WHERE user_id = ?", USER_ID);
    jdbcTemplate.update("DELETE FROM app_users WHERE user_id = ?", USER_ID);
  }
}
