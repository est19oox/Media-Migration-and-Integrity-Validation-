package mmiv.user_access_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Set;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.service.AuthenticationService;
import mmiv.user_access_service.service.InvalidCredentialsException;
import mmiv.user_access_service.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean mmiv.events.EventPublisher auditPublisher;


  private static final User ACTIVE_USER =
      new User(
          "U001",
          List.of(new DestinationAccess("bucket-a", Permission.WRITE)),
          "alice",
          "OPERATOR",
          true,
          Set.of(Permission.READ),
          "$2a$12$stored");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private AuthenticationService authenticationService;

  @MockitoBean private JwtService jwtService;

  @Test
  void successfulLoginReturnsOnlyBearerTokenFields() throws Exception {
    when(authenticationService.authenticate("alice", "password")).thenReturn(ACTIVE_USER);
    when(jwtService.generateToken(ACTIVE_USER)).thenReturn("signed-rs256-token");

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content(
                    """
                                {"username":"alice","password":"password"}
                                """))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    """
                        {"accessToken":"signed-rs256-token","tokenType":"Bearer"}
                        """))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.passwordHash").doesNotExist());

    verify(authenticationService).authenticate("alice", "password");
    verify(jwtService).generateToken(ACTIVE_USER);
  }

  @Test
  void successfulLoginReturnsValidRs256JwtWithExpectedClaims() throws Exception {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(2048);
    KeyPair keyPair = keyPairGenerator.generateKeyPair();
    String issuer = "https://auth.mmiv.example";
    String keyId = "mmiv-login-test-key";
    RSAKey rsaKey =
        new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
            .privateKey((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate())
            .keyID(keyId)
            .build();
    JwtService realJwtService =
        new JwtService(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey))), issuer);
    MockMvc standaloneMvc =
        MockMvcBuilders.standaloneSetup(new AuthController(authenticationService, realJwtService))
            .setControllerAdvice(new AuthExceptionHandler())
            .build();
    when(authenticationService.authenticate("alice", "password")).thenReturn(ACTIVE_USER);

    String token =
        standaloneMvc
            .perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        """
                                {"username":"alice","password":"password"}
                                """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceFirst(".*\"accessToken\":\"([^\"]+)\".*", "$1");

    JwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
            .build();
    var jwt = decoder.decode(token);
    org.junit.jupiter.api.Assertions.assertEquals("RS256", jwt.getHeaders().get("alg"));
    org.junit.jupiter.api.Assertions.assertEquals(keyId, jwt.getHeaders().get("kid"));
    org.junit.jupiter.api.Assertions.assertEquals(issuer, jwt.getIssuer().toString());
    org.junit.jupiter.api.Assertions.assertEquals("U001", jwt.getSubject());
    org.junit.jupiter.api.Assertions.assertEquals(
        1800, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
  }

  @Test
  void invalidCredentialsReturnUnauthorized() throws Exception {
    when(authenticationService.authenticate(any(), any()))
        .thenThrow(new InvalidCredentialsException());

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content(
                    """
                                {"username":"alice","password":"wrong"}
                                """))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Invalid username or password"));

    verify(jwtService, never()).generateToken(any());
  }

  @Test
  void unknownUsernameReturnsUnauthorized() throws Exception {
    invalidCredentialsReturnUnauthorizedFor("unknown", "password");
  }

  @Test
  void inactiveUserReturnsUnauthorized() throws Exception {
    invalidCredentialsReturnUnauthorizedFor("alice", "password");
  }

  @Test
  void missingUsernameReturnsBadRequest() throws Exception {
    invalidRequest(
        """
                {"password":"password"}
                """);
  }

  @Test
  void missingPasswordReturnsBadRequest() throws Exception {
    invalidRequest(
        """
                {"username":"alice"}
                """);
  }

  @Test
  void blankCredentialsReturnBadRequest() throws Exception {
    invalidRequest(
        """
                {"username":" ","password":""}
                """);
  }

  private void invalidCredentialsReturnUnauthorizedFor(String username, String password)
      throws Exception {
    when(authenticationService.authenticate(eq(username), eq(password)))
        .thenThrow(new InvalidCredentialsException());

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content(
                    """
                                {"username":"%s","password":"%s"}
                                """
                        .formatted(username, password)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Invalid username or password"));
  }

  private void invalidRequest(String request) throws Exception {
    mockMvc
        .perform(post("/api/auth/login").contentType("application/json").content(request))
        .andExpect(status().isBadRequest());
    verify(authenticationService, never()).authenticate(any(), any());
  }
}
