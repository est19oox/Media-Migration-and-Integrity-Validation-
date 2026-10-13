package mmiv.user_access_service.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.InMemoryUserRepository;
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
class AccessibleDestinationsSecurityIntegrationTest {

  private static final String ISSUER = "https://auth.mmiv.example";
  private static final String OWNER = "DESTINATIONS-OWNER";
  private static final String OTHER_USER = "DESTINATIONS-OTHER";
  private static final String EMPTY_USER = "DESTINATIONS-EMPTY";
  private static final String INACTIVE_USER = "DESTINATIONS-INACTIVE";
  private static final String REVOCATION_USER = "DESTINATIONS-REVOCATION";
  private static final String GLOBAL_PERMISSION_USER = "DESTINATIONS-GLOBAL";
  private static final String REPOSITORY_FAILURE_USER = "DESTINATIONS-DB-FAILURE";
  private static final String FAILURE_MARKER = "SYNTHETIC_DESTINATIONS_DB_FAILURE";
  private static final String PASSWORD_MARKER = "DESTINATIONS_PRIVATE_PASSWORD_HASH";

  @Autowired private MockMvc mockMvc;
  @Autowired private mmiv.user_access_service.repository.InMemoryFolderRepository folders;

  @Autowired private JwtEncoder jwtEncoder;

  @MockitoSpyBean private InMemoryUserRepository userRepository;

  @Value("${jwt.key-id}")
  private String keyId;

  @BeforeEach
  void setUpUsers() {
    for(String name:List.of("project-A","project-B","other-user-private-folder","inactive-folder","revocable-folder")) folders.create(name);
    userRepository.save(
        user(
            OWNER,
            List.of(
                new DestinationAccess("project-B", Permission.READ),
                new DestinationAccess("project-A", Permission.WRITE)),
            Set.of()));
    userRepository.save(
        user(
            OTHER_USER,
            List.of(new DestinationAccess("other-user-private-folder", Permission.WRITE)),
            Set.of()));
    userRepository.save(user(EMPTY_USER, List.of(), Set.of()));
    userRepository.save(
        user(
            INACTIVE_USER,
            List.of(new DestinationAccess("inactive-folder", Permission.WRITE)),
            Set.of()));
    userRepository.save(
        user(
            REVOCATION_USER,
            List.of(new DestinationAccess("revocable-folder", Permission.WRITE)),
            Set.of()));
    userRepository.grantDestinationAccess(REVOCATION_USER, "revocable-folder", Permission.WRITE);
    userRepository.save(user(GLOBAL_PERMISSION_USER, List.of(), Set.of(Permission.WRITE)));
    userRepository.save(user(REPOSITORY_FAILURE_USER, List.of(), Set.of()));
  }

  @Test
  void returnsOnlyAuthenticatedUsersCurrentAssignmentsInDeterministicOrder() throws Exception {
    MvcResult result =
        destinations(token(OWNER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.destinations.length()").value(2))
            .andExpect(jsonPath("$.destinations[0].destination").value("project-A"))
            .andExpect(jsonPath("$.destinations[0].permission").value("WRITE"))
            .andExpect(jsonPath("$.destinations[1].destination").value("project-B"))
            .andExpect(jsonPath("$.destinations[1].permission").value("READ"))
            .andReturn();

    assertFalse(result.getResponse().getContentAsString().contains("other-user-private-folder"));
    assertFalse(result.getResponse().getContentAsString().contains(PASSWORD_MARKER));
  }

  @Test
  void ignoresAttemptToSelectAnotherUsersAssignments() throws Exception {
    mockMvc
        .perform(
            get("/api/access/destinations")
                .queryParam("userId", OTHER_USER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(OWNER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destinations.length()").value(2))
        .andExpect(jsonPath("$.destinations[0].destination").value("project-A"))
        .andExpect(jsonPath("$.destinations[1].destination").value("project-B"));
  }

  @Test
  void activeUserWithoutAssignmentsReceivesEmptyList() throws Exception {
    destinations(token(EMPTY_USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destinations").isArray())
        .andExpect(jsonPath("$.destinations.length()").value(0));
  }

  @Test
  void inactiveUserIsDenied() throws Exception {
    destinations(token(INACTIVE_USER)).andExpect(status().isForbidden());
  }

  @Test
  void missingJwtIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/access/destinations")).andExpect(status().isUnauthorized());
  }

  @Test
  void invalidJwtIsUnauthorized() throws Exception {
    destinations("not-a-valid-jwt").andExpect(status().isUnauthorized());
  }

  @Test
  void missingJwtSubjectIsRejected() throws Exception {
    destinations(tokenWithoutSubject(null)).andExpect(status().isForbidden());
  }

  @Test
  void blankJwtSubjectIsRejected() throws Exception {
    destinations(tokenWithoutSubject("   ")).andExpect(status().isForbidden());
  }

  @Test
  void revokedAssignmentIsAbsentOnTheNextRequest() throws Exception {
    destinations(token(REVOCATION_USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destinations.length()").value(1))
        .andExpect(jsonPath("$.destinations[0].permission").value("WRITE"));

    userRepository.revokeDestinationAccess(REVOCATION_USER, "revocable-folder");

    destinations(token(REVOCATION_USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destinations.length()").value(0));
  }

  @Test
  void globalWritePermissionDoesNotCreateDestinationAssignments() throws Exception {
    destinations(token(GLOBAL_PERMISSION_USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destinations.length()").value(0));
  }

  @Test
  void repositoryFailureReturnsSanitizedServerError() throws Exception {
    doThrow(new DataAccessResourceFailureException(FAILURE_MARKER + " " + PASSWORD_MARKER))
        .when(userRepository)
        .findById(REPOSITORY_FAILURE_USER);

    MvcResult result =
        destinations(token(REPOSITORY_FAILURE_USER))
            .andExpect(status().is5xxServerError())
            .andReturn();

    String responseBody = result.getResponse().getContentAsString();
    assertFalse(responseBody.contains(FAILURE_MARKER));
    assertFalse(responseBody.contains(PASSWORD_MARKER));
    assertFalse(responseBody.contains(DataAccessResourceFailureException.class.getName()));
  }

  private User user(
      String userId, List<DestinationAccess> destinationAccesses, Set<Permission> permissions) {
    return new User(
        userId,
        destinationAccesses,
        userId,
        "TEST",
        !INACTIVE_USER.equals(userId),
        permissions,
        PASSWORD_MARKER);
  }

  private org.springframework.test.web.servlet.ResultActions destinations(String bearerToken)
      throws Exception {
    var request = get("/api/access/destinations");
    if (bearerToken != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }
    return mockMvc.perform(request.accept(MediaType.APPLICATION_JSON));
  }

  private String token(String subject) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(subject)
            .issuedAt(Instant.now().minusSeconds(1))
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    return encode(claims);
  }

  private String tokenWithoutSubject(String subject) {
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .issuedAt(Instant.now().minusSeconds(1))
            .expiresAt(Instant.now().plusSeconds(300));
    if (subject != null) {
      claims.subject(subject);
    }
    return encode(claims.build());
  }

  private String encode(JwtClaimsSet claims) {
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
