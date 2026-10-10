package mmiv.user_access_service.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class RepositoryFailureSecurityIntegrationTest {

  private static final String USER_ID = "DB-FAILURE-USER";
  private static final String ISSUER = "https://auth.mmiv.example";
  private static final String EXCEPTION_MARKER = "SYNTHETIC_DB_FAILURE_MARKER";
  private static final String SQL_MARKER = "SELECT * FROM synthetic_user_table";
  private static final String CREDENTIAL_MARKER = "password=SYNTHETIC_TEST_ONLY";

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private JwtEncoder jwtEncoder;

  @MockitoBean private UserRepository userRepository;

  @LocalServerPort private int port;

  @Value("${jwt.key-id}")
  private String keyId;

  @Test
  void repositoryFailureNeverAuthorizesOrLeaksInternalDetails() throws Exception {
    doThrow(
            new DataAccessResourceFailureException(
                EXCEPTION_MARKER + " " + SQL_MARKER + " " + CREDENTIAL_MARKER))
        .when(userRepository)
        .findById(USER_ID);

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(validToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> request =
        new HttpEntity<>(
            """
            {
              "userId": "DB-FAILURE-USER",
              "destination": "DEST_A",
              "action": "WRITE"
            }
            """,
            headers);
    ResponseEntity<String> response =
        restTemplate.exchange(
            "http://localhost:" + port + "/api/access/check",
            HttpMethod.POST,
            request,
            String.class);

    verify(userRepository).findById(USER_ID);
    String responseBody = response.getBody() == null ? "" : response.getBody();
    assertFalse(responseBody.contains("\"authorised\":true"));
    assertFalse(responseBody.contains(EXCEPTION_MARKER));
    assertFalse(responseBody.contains(SQL_MARKER));
    assertFalse(responseBody.contains(CREDENTIAL_MARKER));
    assertFalse(responseBody.contains(DataAccessResourceFailureException.class.getName()));

    int statusCode = response.getStatusCode().value();
    assertTrue(
        statusCode >= 500 && statusCode < 600,
        "Expected an infrastructure-error status, got HTTP " + statusCode);
  }

  private String validToken() {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(USER_ID)
            .issuedAt(Instant.now().minusSeconds(1))
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
