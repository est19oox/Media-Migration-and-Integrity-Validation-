package mmiv.user_access_service.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.dto.AccessCheckRequest;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.InMemoryUserRepository;
import mmiv.user_access_service.service.AccessService;
import mmiv.user_access_service.service.AuthenticationService;
import mmiv.user_access_service.service.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class UserManagementSecurityIntegrationTest {

  private static final String ISSUER = "https://auth.mmiv.example";
  private static final String ADMIN_PASSWORD = "Stage8-admin-password";
  private static final String NEW_PASSWORD = "Stage8-new-user-password";
  private static final String FAILURE_MARKER = "STAGE8_PRIVATE_REPOSITORY_FAILURE";
  private static final AtomicLong IDS = new AtomicLong();

  @Autowired private MockMvc mockMvc;

  @Autowired private JwtEncoder jwtEncoder;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private AuthenticationService authenticationService;

  @MockitoSpyBean private InMemoryUserRepository userRepository;

  @Value("${jwt.key-id}")
  private String keyId;

  private String administratorId;
  private String administratorUsername;
  private String targetUserId;
  private String targetUsername;

  @BeforeEach
  void setUpUsers() {
    String suffix = Long.toString(IDS.incrementAndGet());
    administratorId = "STAGE8-ADMIN-" + suffix;
    administratorUsername = "stage8-admin-" + suffix;
    targetUserId = "STAGE8-TARGET-" + suffix;
    targetUsername = "stage8-target-" + suffix;

    userRepository.save(
        user(
            administratorId,
            administratorUsername,
            "ADMIN",
            true,
            passwordEncoder.encode(ADMIN_PASSWORD),
            List.of()));
    userRepository.save(
        user(
            targetUserId,
            targetUsername,
            "USER",
            true,
            passwordEncoder.encode("Stage8-target-password"),
            List.of(new DestinationAccess("stage8-destination", Permission.WRITE))));
  }

  @Test
  void administratorCreatesOnlyAnOrdinaryUserAndNeverReturnsPasswordData() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/users")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(administratorId, "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                            {
                              "username": "stage8-created-user",
                              "password": "%s",
                              "role": "USER"
                            }
                            """
                            .formatted(NEW_PASSWORD)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.username").value("stage8-created-user"))
            .andExpect(jsonPath("$.role").value("USER"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    User created = userRepository.findByUsername("stage8-created-user").orElseThrow();
    assertNotNull(created.getUserId());
    assertFalse(created.getUserId().isBlank());
    assertNotNull(created.getPasswordHash());
    assertFalse(created.getPasswordHash().equals(NEW_PASSWORD));
    assertTrue(passwordEncoder.matches(NEW_PASSWORD, created.getPasswordHash()));
    assertFalse(response.contains(NEW_PASSWORD));
    assertFalse(response.contains(created.getPasswordHash()));
    assertFalse(response.contains("passwordHash"));
  }

  @Test
  void createUserRejectsMissingJwt() throws Exception {
    mockMvc
        .perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createUserJson("stage8-no-token", NEW_PASSWORD, "USER")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void createUserRejectsInvalidJwt() throws Exception {
    mockMvc
        .perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createUserJson("stage8-invalid-token", NEW_PASSWORD, "USER")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void ordinaryUserAndForgedAdministratorClaimCannotCreateUsers() throws Exception {
    String username = "stage8-not-admin";
    userRepository.save(
        user(
            "STAGE8-ORDINARY",
            username,
            "USER",
            true,
            passwordEncoder.encode("Stage8-ordinary-password"),
            List.of()));

    mockMvc
        .perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("STAGE8-ORDINARY", "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createUserJson("stage8-forged-role", NEW_PASSWORD, "USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void staleAdministratorClaimIsRejectedWhenRepositoryRoleIsOrdinary() throws Exception {
    mockMvc
        .perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(targetUserId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createUserJson("stage8-stale-admin", NEW_PASSWORD, "USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void inactiveAdministratorCannotCreateUsers() throws Exception {
    userRepository.save(
        user(
            administratorId,
            administratorUsername,
            "ADMIN",
            false,
            passwordEncoder.encode(ADMIN_PASSWORD),
            List.of()));

    mockMvc
        .perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(administratorId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createUserJson("stage8-inactive-admin", NEW_PASSWORD, "USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void createUserCannotGrantAdministratorRoleWithoutAnApprovedPolicy() throws Exception {
    mockMvc
        .perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(administratorId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createUserJson("stage8-requested-admin", NEW_PASSWORD, "ADMIN")))
        .andExpect(status().isBadRequest());

    assertTrue(userRepository.findByUsername("stage8-requested-admin").isEmpty());
  }

  @Test
  void createUserRejectsBlankUsernameAndPassword() throws Exception {
    String bearer = "Bearer " + token(administratorId, "ADMIN");

    mockMvc
        .perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createUserJson(" ", NEW_PASSWORD, "USER")))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createUserJson("stage8-blank-password", " ", "USER")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createUserRejectsDuplicateUsername() throws Exception {
    mockMvc
        .perform(
            post("/api/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(administratorId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createUserJson(targetUsername, NEW_PASSWORD, "USER")))
        .andExpect(status().isConflict());
  }

  @Test
  void createUserFailsClosedAndSanitizesRepositoryFailures() throws Exception {
    org.mockito.Mockito.doThrow(new DataAccessResourceFailureException(FAILURE_MARKER))
        .when(userRepository)
        .findById(administratorId);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/users")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(administratorId, "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createUserJson("stage8-repository-failure", NEW_PASSWORD, "USER")))
            .andExpect(status().is5xxServerError())
            .andReturn();

    assertFalse(result.getResponse().getContentAsString().contains(FAILURE_MARKER));
    assertFalse(
        result.getResponse().getContentAsString().contains("DataAccessResourceFailureException"));
    assertTrue(userRepository.findByUsername("stage8-repository-failure").isEmpty());
  }

  @Test
  void administratorDeactivatesTargetAndExistingAccessIsDenied() throws Exception {
    String response =
        mockMvc
            .perform(
                patch("/api/users/{userId}/active", targetUserId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(administratorId, "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"active\":false}"))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();

    User deactivated = userRepository.findById(targetUserId).orElseThrow();
    assertFalse(deactivated.isActive());
    assertFalse(response.contains(deactivated.getPasswordHash()));
    assertFalse(response.contains("passwordHash"));
    org.junit.jupiter.api.Assertions.assertEquals(
        List.of(new DestinationAccess("stage8-destination", Permission.WRITE)),
        deactivated.getDestinationAccesses());
    org.junit.jupiter.api.Assertions.assertThrows(
        InvalidCredentialsException.class,
        () -> authenticationService.authenticate(targetUsername, "Stage8-target-password"));

    AccessCheckRequest accessRequest = new AccessCheckRequest();
    accessRequest.setUserId(targetUserId);
    accessRequest.setDestination("stage8-destination");
    accessRequest.setAction("WRITE");
    assertFalse(new AccessService(userRepository).checkAccess(accessRequest).isAuthorised());
  }

  @Test
  void administratorCanReactivateTargetWithoutChangingItsPermissions() throws Exception {
    userRepository.save(
        user(
            targetUserId,
            targetUsername,
            "USER",
            false,
            userRepository.findById(targetUserId).orElseThrow().getPasswordHash(),
            List.of(new DestinationAccess("stage8-destination", Permission.WRITE))));

    mockMvc
        .perform(
            patch("/api/users/{userId}/active", targetUserId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(administratorId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"))
        .andExpect(status().is2xxSuccessful());

    User reactivated = userRepository.findById(targetUserId).orElseThrow();
    assertTrue(reactivated.isActive());
    org.junit.jupiter.api.Assertions.assertEquals(
        List.of(new DestinationAccess("stage8-destination", Permission.WRITE)),
        reactivated.getDestinationAccesses());
    org.junit.jupiter.api.Assertions.assertEquals(
        targetUsername,
        authenticationService.authenticate(targetUsername, "Stage8-target-password").getUsername());
  }

  @Test
  void administratorCannotReactivateAnInactiveAdministrator() throws Exception {
    String inactiveAdministratorId = "STAGE8-INACTIVE-ADMIN";
    userRepository.save(
        user(
            inactiveAdministratorId,
            "stage8-inactive-admin",
            "ADMIN",
            false,
            passwordEncoder.encode("Stage8-inactive-admin-password"),
            List.of()));

    mockMvc
        .perform(
            patch("/api/users/{userId}/active", inactiveAdministratorId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(administratorId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"))
        .andExpect(status().isConflict());

    assertFalse(userRepository.findById(inactiveAdministratorId).orElseThrow().isActive());
  }

  @Test
  void ordinaryUserCannotChangeAccountActivity() throws Exception {
    mockMvc
        .perform(
            patch("/api/users/{userId}/active", targetUserId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(targetUserId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isForbidden());

    assertTrue(userRepository.findById(targetUserId).orElseThrow().isActive());
  }

  @Test
  void administratorCannotDeactivateTheirOwnAccountOrTheLastAdministrator() throws Exception {
    mockMvc
        .perform(
            patch("/api/users/{userId}/active", administratorId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(administratorId, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isConflict());

    assertTrue(userRepository.findById(administratorId).orElseThrow().isActive());
  }

  @Test
  void deactivateUserRejectsMissingActiveValueAndUnknownUser() throws Exception {
    String bearer = "Bearer " + token(administratorId, "ADMIN");

    mockMvc
        .perform(
            patch("/api/users/{userId}/active", targetUserId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            patch("/api/users/{userId}/active", "STAGE8-MISSING")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deactivateUserFailsClosedAndSanitizesRepositoryFailures() throws Exception {
    org.mockito.Mockito.doThrow(new DataAccessResourceFailureException(FAILURE_MARKER))
        .when(userRepository)
        .findById(administratorId);

    MvcResult result =
        mockMvc
            .perform(
                patch("/api/users/{userId}/active", targetUserId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(administratorId, "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"active\":false}"))
            .andExpect(status().is5xxServerError())
            .andReturn();

    assertFalse(result.getResponse().getContentAsString().contains(FAILURE_MARKER));
    assertFalse(
        result.getResponse().getContentAsString().contains("DataAccessResourceFailureException"));
    assertTrue(userRepository.findById(targetUserId).orElseThrow().isActive());
  }

  private String token(String subject, String claimedRole) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(subject)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("role", claimedRole)
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  private static String createUserJson(String username, String password, String role) {
    return """
        {"username":"%s","password":"%s","role":"%s"}
        """
        .formatted(username, password, role);
  }

  private static User user(
      String userId,
      String username,
      String role,
      boolean active,
      String passwordHash,
      List<DestinationAccess> destinations) {
    return new User(
        userId, destinations, username, role, active, Set.of(Permission.READ), passwordHash);
  }
}
