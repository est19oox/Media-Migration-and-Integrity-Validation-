package mmiv.user_access_service.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;
import java.util.Set;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.OracleUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "eureka.client.enabled=false",
      "jwt.issuer=https://auth.mmiv.example",
      "jwt.key-id=mmiv-integration-key"
    })
@ActiveProfiles("integration")
@Import(TestJwtConfig.class)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
      "spring.datasource.username=USER_ACCESS"
    })
class AuthLoginIT {

  private static final String USER_ID = "IT_LOGIN_" + "6b4e9c1d";
  private static final String USERNAME = "it_login_6b4e9c1d";
  private static final String PASSWORD = "integration-password";

  @Autowired private MockMvc mockMvc;

  @Autowired private OracleUserRepository repository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private JwtDecoder jwtDecoder;

  @BeforeEach
  void createIsolatedUser() {
    cleanup();
    repository.save(
        new User(
            USER_ID,
            List.of(),
            USERNAME,
            "OPERATOR",
            true,
            Set.of(),
            passwordEncoder.encode(PASSWORD)));
  }

  @AfterEach
  void cleanupIsolatedUser() {
    cleanup();
  }

  @Test
  void correctCredentialsReturnJwtVerifiedByPublishedJwks() throws Exception {
    String token = login(PASSWORD);
    String jwks =
        mockMvc
            .perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    RSAKey publicKey = (RSAKey) JWKSet.parse(jwks).getKeys().get(0);
    assertEquals("mmiv-integration-key", publicKey.getKeyID());
    assertFalse(jwks.contains("\"d\""));
    assertFalse(jwks.contains("\"p\""));
    assertFalse(jwks.contains("\"q\""));
    assertFalse(jwks.contains(PASSWORD));

    var jwt = jwtDecoder.decode(token);
    assertEquals("RS256", jwt.getHeaders().get("alg"));
    assertEquals("mmiv-integration-key", jwt.getHeaders().get("kid"));
    assertEquals("https://auth.mmiv.example", jwt.getIssuer().toString());
    assertEquals(USER_ID, jwt.getSubject());
    assertEquals(1800, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
    assertNotNull(publicKey.toRSAPublicKey());
    assertTrue(jwt.getTokenValue().equals(token));
  }

  @Test
  void incorrectPasswordReturnsUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content(
                    """
                                {"username":"it_login_6b4e9c1d","password":"wrong"}
                                """))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void inactiveUserReturnsUnauthorized() throws Exception {
    repository.save(
        new User(USER_ID, USERNAME, "OPERATOR", false, Set.of(), passwordEncoder.encode(PASSWORD)));

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content(
                    """
                                {"username":"it_login_6b4e9c1d","password":"integration-password"}
                                """))
        .andExpect(status().isUnauthorized());
  }

  private String login(String password) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        """
                                {"username":"it_login_6b4e9c1d","password":"%s"}
                                """
                            .formatted(password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return response.replaceFirst(".*\"accessToken\":\"([^\"]+)\".*", "$1");
  }

  private void cleanup() {
    jdbcTemplate.update("DELETE FROM user_destination_access WHERE user_id = ?", USER_ID);
    jdbcTemplate.update("DELETE FROM user_permissions WHERE user_id = ?", USER_ID);
    jdbcTemplate.update("DELETE FROM app_users WHERE user_id = ?", USER_ID);
  }
}
