package mmiv.user_access_service.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.Set;
import mmiv.events.EventPublisher;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.InMemoryUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "mmiv.admin.destination-id=landing")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class AdminSecurityIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired InMemoryUserRepository users;
  @Autowired mmiv.user_access_service.repository.InMemoryFolderRepository folders;
  @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;

  @org.springframework.beans.factory.annotation.Value("${jwt.key-id}")
  String keyId;

  @MockitoBean EventPublisher events;
  static final String ADMIN = "ADMIN-TEST", TARGET = "TARGET-TEST", OTHER = "OTHER-TEST";

  @BeforeEach
  void users() {
    folders.create("landing");
    users.save(new User(ADMIN, "site-admin", "ADMIN", true, Set.of(), "private-hash"));
    users.save(new User(TARGET, "outside-user", "USER", true, Set.of(), "private-hash"));
    users.save(new User(OTHER, "other-user", "USER", true, Set.of(), "private-hash"));
    users.revokeDestinationAccess(TARGET, "landing");
    users.revokeDestinationAccess(TARGET, "SW001");
    users.revokeDestinationAccess(TARGET, "semester2");
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor caller(String id) {
    return caller(id, "ADMIN");
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor caller(
      String id, String role) {
    var claims =
        org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
            .issuer("https://auth.mmiv.example")
            .subject(id)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("role", role)
            .build();
    var header =
        org.springframework.security.oauth2.jwt.JwsHeader.with(
                org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
            .keyId(keyId)
            .build();
    String token =
        encoder
            .encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims))
            .getTokenValue();
    return request -> {
      request.addHeader("Authorization", "Bearer " + token);
      return request;
    };
  }

  @Test
  void anonymousAndMalformedTokensAreRejected() throws Exception {
    mvc.perform(get("/api/admin/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/me").header("Authorization", "Bearer broken"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void nonexistentPermissionAndAdministratorTargetsReturnNotFound() throws Exception {
    mvc.perform(
            put("/api/admin/users/missing-user/destinations")
                .with(caller(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destination\":\"landing\",\"permission\":\"WRITE\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/admin/invitations")
                .with(caller(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"does-not-exist\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void onlyAdministratorCanDeleteRegisteredFolderAndGrantsAreRemoved() throws Exception {
    folders.create("delete-test");
    users.grantDestinationAccess(
        TARGET, "delete-test", mmiv.user_access_service.model.Permission.WRITE);
    mvc.perform(delete("/api/admin/folders/delete-test").with(caller(TARGET)))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/admin/folders/delete-test").with(caller(ADMIN)))
        .andExpect(status().isOk());
    assertFalse(folders.exists("delete-test"));
    assertTrue(
        users.findById(TARGET).orElseThrow().getDestinationAccesses().stream()
            .noneMatch(a -> a.getDestination().equals("delete-test")));
  }

  @Test
  void folderCreationAndSearchRequireCurrentAdministratorRole() throws Exception {
    mvc.perform(
            post("/api/admin/folders")
                .with(caller(TARGET))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"SW001\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/folders")
                .with(caller(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"../escape\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/admin/folders")
                .with(caller(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"SW001\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/user-options").with(caller(TARGET)))
        .andExpect(status().isForbidden());
    var body =
        mvc.perform(get("/api/admin/user-options").param("q", "outside").with(caller(ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].username").value("outside-user"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("private-hash"));
  }

  @Test
  void profileShowsOnlyRegisteredGrantedFoldersAndReadCannotUpload() throws Exception {
    folders.create("semester2");
    folders.create("SW001");
    users.grantDestinationAccess(TARGET, "SW001", mmiv.user_access_service.model.Permission.READ);
    users.grantDestinationAccess(
        TARGET, "old-bucket", mmiv.user_access_service.model.Permission.WRITE);
    mvc.perform(get("/api/access/me").with(caller(TARGET)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destinations.length()").value(1))
        .andExpect(jsonPath("$.destinations[0].destination").value("SW001"));
    mvc.perform(get("/api/access/folders/semester2").with(caller(TARGET)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/access/check")
                .with(caller(TARGET))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"userId\":\""
                        + TARGET
                        + "\",\"destination\":\"SW001\",\"action\":\"WRITE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(false));
    users.revokeDestinationAccess(TARGET, "SW001");
    users.revokeDestinationAccess(TARGET, "old-bucket");
  }

  @Test
  void ordinaryUserCannotUseForgedAdminClaim() throws Exception {
    mvc.perform(get("/api/admin/me").with(caller(TARGET))).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/invitations")
                .with(caller(TARGET))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"other-user\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void administratorLookupNeverExposesHashes() throws Exception {
    var body =
        mvc.perform(get("/api/admin/users").param("username", "outside-user").with(caller(ADMIN)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.userId").value(TARGET))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("private-hash"));
    assertFalse(body.contains("passwordHash"));
  }

  @Test
  void grantDowngradeAndRevokeUpdateCurrentPermissions() throws Exception {
    for (String permission : new String[] {"WRITE", "READ"}) {
      mvc.perform(
              put("/api/admin/users/" + TARGET + "/destinations")
                  .with(caller(ADMIN))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"destination\":\"landing\",\"permission\":\"" + permission + "\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.destinations[0].permission").value(permission));
    }
    mvc.perform(
            delete("/api/admin/users/" + TARGET + "/destinations")
                .param("destination", "landing")
                .with(caller(ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destinations").isEmpty());
    verify(events).audit(eq("ADMIN_PERMISSION_REVOKED"), anyMap());
  }

  @Test
  void grantsAndRevokesTheSelectedFolderInsteadOfTheOldConfiguredDestination() throws Exception {
    folders.create("semester2");
    mvc.perform(
            put("/api/admin/users/" + TARGET + "/destinations")
                .with(caller(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destination\":\"semester2\",\"permission\":\"WRITE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destinations.length()").value(1))
        .andExpect(jsonPath("$.destinations[0].destination").value("semester2"));
    mvc.perform(
            delete("/api/admin/users/" + TARGET + "/destinations")
                .param("destination", "semester2")
                .with(caller(ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destinations").isEmpty());
  }

  @Test
  void demotionAndInactiveStatusOverrideExistingJwt() throws Exception {
    var token = caller(ADMIN);
    mvc.perform(get("/api/admin/me").with(token)).andExpect(status().isOk());
    users.save(new User(ADMIN, "site-admin", "USER", true, Set.of(), null));
    mvc.perform(get("/api/admin/me").with(token)).andExpect(status().isForbidden());
    users.save(new User(ADMIN, "site-admin", "ADMIN", false, Set.of(), null));
    mvc.perform(get("/api/admin/me").with(token)).andExpect(status().isForbidden());
  }

  @Test
  void unknownDestinationAndInvalidPermissionAreRejected() throws Exception {
    mvc.perform(
            put("/api/admin/users/" + TARGET + "/destinations")
                .with(caller(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destination\":\"other-bucket\",\"permission\":\"WRITE\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/api/admin/users/" + TARGET + "/destinations")
                .with(caller(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destination\":\"landing\",\"permission\":\"ADMIN\"}"))
        .andExpect(status().isBadRequest());
    assertTrue(users.findById(TARGET).orElseThrow().getDestinationAccesses().isEmpty());
  }

  private String invitation() throws Exception {
    String body =
        mvc.perform(
                post("/api/admin/invitations")
                    .with(caller(ADMIN))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"outside-user\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return tools.jackson.databind.json.JsonMapper.builder()
        .build()
        .readTree(body)
        .get("token")
        .asString();
  }

  @Test
  void invitationIsAccountBoundAndCanOnlyBeRedeemedOnce() throws Exception {
    String token = invitation();
    mvc.perform(
            post("/api/admin/invitations/accept")
                .with(caller(OTHER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/invitations/accept")
                .with(caller(TARGET))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/me").with(caller(TARGET, "USER"))).andExpect(status().isOk());
    mvc.perform(
            post("/api/admin/invitations/accept")
                .with(caller(TARGET))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isForbidden());
    assertEquals("private-hash", users.findById(TARGET).orElseThrow().getPasswordHash());
    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<java.util.Map<String, Object>> data =
        org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
    verify(events).audit(eq("ADMIN_INVITATION_CREATED"), data.capture());
    assertFalse(data.getValue().toString().contains(token));
  }

  @Test
  void expiredInvitationIsRejectedWithoutRoleChange() throws Exception {
    String token = "b".repeat(64);
    String hash =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    users.createAdminInvitation(TARGET, hash, Instant.now().minusSeconds(1), ADMIN);
    mvc.perform(
            post("/api/admin/invitations/accept")
                .with(caller(TARGET))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isForbidden());
    assertEquals("USER", users.findById(TARGET).orElseThrow().getRole());
  }

  @Test
  void inactiveInviteeCannotRedeemInvitation() throws Exception {
    String token = invitation();
    users.save(new User(TARGET, "outside-user", "USER", false, Set.of(), null));
    mvc.perform(
            post("/api/admin/invitations/accept")
                .with(caller(TARGET))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isForbidden());
  }
}
