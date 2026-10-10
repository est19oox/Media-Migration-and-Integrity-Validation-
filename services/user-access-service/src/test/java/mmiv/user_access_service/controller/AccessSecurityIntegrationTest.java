package mmiv.user_access_service.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class AccessSecurityIntegrationTest {

  private static final String ISSUER = "https://auth.mmiv.example";
  private static final String VALID_USER = "SEC-VALID";
  private static final String READ_USER = "SEC-READ";
  private static final String WRITE_USER = "SEC-WRITE";
  private static final String GLOBAL_PERMISSION_USER = "SEC-GLOBAL";
  private static final String REVOKE_USER = "SEC-REVOKE";
  private static final String INACTIVE_USER = "SEC-INACTIVE";

  @Autowired private MockMvc mockMvc;

  @Autowired private JwtEncoder jwtEncoder;

  @Autowired private RSAPrivateKey testPrivateKey;

  @Autowired private UserRepository userRepository;

  @Value("${jwt.key-id}")
  private String keyId;

  @BeforeEach
  void setUpUsers() {
    userRepository.save(
        user(VALID_USER, List.of(new DestinationAccess("DEST_VALID", Permission.READ)), Set.of()));
    userRepository.save(
        user(READ_USER, List.of(new DestinationAccess("DEST_READ", Permission.READ)), Set.of()));
    userRepository.save(
        user(WRITE_USER, List.of(new DestinationAccess("DEST_WRITE", Permission.WRITE)), Set.of()));
    userRepository.save(user(GLOBAL_PERMISSION_USER, List.of(), Set.of(Permission.WRITE)));
    userRepository.save(
        user(
            REVOKE_USER,
            List.of(new DestinationAccess("DEST_REVOKE", Permission.WRITE)),
            Set.of()));
    userRepository.save(
        user(
            INACTIVE_USER,
            List.of(new DestinationAccess("DEST_INACTIVE", Permission.WRITE)),
            Set.of()));
  }

  @Test
  void missingJwtIsUnauthorized() throws Exception {
    postAccess(VALID_USER, "DEST_VALID", "READ", null).andExpect(status().isUnauthorized());
  }

  @Test
  void validRs256JwtCanAccessAuthorizedDestination() throws Exception {
    postAccess(
            VALID_USER,
            "DEST_VALID",
            "READ",
            token(VALID_USER, ISSUER, Instant.now().plusSeconds(300)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(true))
        .andExpect(jsonPath("$.reason").value("Access granted"));
  }

  @Test
  void signedRs256JwtWithoutSubjectCannotAuthorizeAccess() throws Exception {
    assertSubjectlessTokenRejected(signedTokenWithoutSubject(null));
  }

  @Test
  void signedRs256JwtWithEmptySubjectCannotAuthorizeAccess() throws Exception {
    assertSubjectlessTokenRejected(signedTokenWithoutSubject(""));
  }

  @Test
  void signedRs256JwtWithWhitespaceSubjectCannotAuthorizeAccess() throws Exception {
    assertSubjectlessTokenRejected(signedTokenWithoutSubject("   "));
  }

  @Test
  void expiredJwtIsUnauthorized() throws Exception {
    postAccess(
            VALID_USER,
            "DEST_VALID",
            "READ",
            token(VALID_USER, ISSUER, Instant.now().minusSeconds(60)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void incorrectIssuerIsUnauthorized() throws Exception {
    postAccess(
            VALID_USER,
            "DEST_VALID",
            "READ",
            token(VALID_USER, "https://wrong-issuer.example", Instant.now().plusSeconds(300)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void invalidRsaSignatureIsUnauthorized() throws Exception {
    var otherKeyPair = generateRsaKeyPair();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
            claims(VALID_USER, ISSUER, Instant.now().plusSeconds(300)));
    jwt.sign(new RSASSASigner(otherKeyPair.getPrivate()));

    postAccess(VALID_USER, "DEST_VALID", "READ", jwt.serialize())
        .andExpect(status().isUnauthorized());
  }

  @Test
  void hs256JwtIsUnauthorized() throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            claims(VALID_USER, ISSUER, Instant.now().plusSeconds(300)));
    jwt.sign(new MACSigner(new byte[32]));

    postAccess(VALID_USER, "DEST_VALID", "READ", jwt.serialize())
        .andExpect(status().isUnauthorized());
  }

  @Test
  void jwtSubjectMismatchIsForbidden() throws Exception {
    postAccess(
            VALID_USER,
            "DEST_VALID",
            "READ",
            token(READ_USER, ISSUER, Instant.now().plusSeconds(300)))
        .andExpect(status().isForbidden());
  }

  @Test
  void authorizedReadIsAllowed() throws Exception {
    postAccess(
            READ_USER,
            "DEST_READ",
            "READ",
            token(READ_USER, ISSUER, Instant.now().plusSeconds(300)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(true));
  }

  @Test
  void authorizedWriteIsAllowed() throws Exception {
    postAccess(
            WRITE_USER,
            "DEST_WRITE",
            "WRITE",
            token(WRITE_USER, ISSUER, Instant.now().plusSeconds(300)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(true));
  }

  @Test
  void destinationWritePermissionAlsoAllowsRead() throws Exception {
    postAccess(
            WRITE_USER,
            "DEST_WRITE",
            "READ",
            token(WRITE_USER, ISSUER, Instant.now().plusSeconds(300)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(true));
  }

  @Test
  void destinationReadPermissionDoesNotAllowWrite() throws Exception {
    postAccess(
            READ_USER,
            "DEST_READ",
            "WRITE",
            token(READ_USER, ISSUER, Instant.now().plusSeconds(300)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(false));
  }

  @Test
  void unauthorizedDestinationReturnsDeniedResponse() throws Exception {
    postAccess(
            READ_USER,
            "DEST_OTHER",
            "READ",
            token(READ_USER, ISSUER, Instant.now().plusSeconds(300)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(false));
  }

  @Test
  void globalJwtAndUserPermissionsDoNotBypassDestinationAuthorization() throws Exception {
    postAccess(
            GLOBAL_PERMISSION_USER,
            "DEST_WITHOUT_GRANT",
            "WRITE",
            tokenWithGlobalWritePermission(GLOBAL_PERMISSION_USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(false));
  }

  @Test
  void revokedDestinationAccessIsRejectedOnTheNextRequest() throws Exception {
    String jwt = token(REVOKE_USER, ISSUER, Instant.now().plusSeconds(300));

    postAccess(REVOKE_USER, "DEST_REVOKE", "WRITE", jwt)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(true));

    userRepository.revokeDestinationAccess(REVOKE_USER, "DEST_REVOKE");

    postAccess(REVOKE_USER, "DEST_REVOKE", "WRITE", jwt)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(false));
  }

  @Test
  void inactiveUserWithWriteGrantIsDeniedRead() throws Exception {
    String jwt = deactivateAfterTokenIssuance();

    postAccess(INACTIVE_USER, "DEST_INACTIVE", "READ", jwt)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(false));
  }

  @Test
  void inactiveUserWithWriteGrantIsDeniedWrite() throws Exception {
    String jwt = deactivateAfterTokenIssuance();

    postAccess(INACTIVE_USER, "DEST_INACTIVE", "WRITE", jwt)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorised").value(false));
  }

  private String deactivateAfterTokenIssuance() {
    String jwt = token(INACTIVE_USER, ISSUER, Instant.now().plusSeconds(300));
    userRepository.save(
        new User(
            INACTIVE_USER,
            List.of(),
            INACTIVE_USER,
            "TEST",
            false,
            Set.of(Permission.WRITE),
            "not-used-for-access"));
    return jwt;
  }

  private User user(
      String userId, List<DestinationAccess> destinationAccesses, Set<Permission> permissions) {
    return new User(
        userId, destinationAccesses, userId, "TEST", true, permissions, "not-used-for-access");
  }

  private org.springframework.test.web.servlet.ResultActions postAccess(
      String userId, String destination, String action, String bearerToken) throws Exception {
    var request =
        post("/api/access/check")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "userId": "%s",
                  "destination": "%s",
                  "action": "%s"
                }
                """
                    .formatted(userId, destination, action));
    if (bearerToken != null) {
      request.header("Authorization", "Bearer " + bearerToken);
    }
    return mockMvc.perform(request);
  }

  private String token(String subject, String issuer, Instant expiresAt) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(subject)
            .issuedAt(expiresAt.minusSeconds(120))
            .expiresAt(expiresAt)
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  private String signedTokenWithoutSubject(String subject) throws Exception {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .issueTime(Date.from(Instant.now().minusSeconds(1)))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    if (subject != null) {
      claims.subject(subject);
    }

    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims.build());
    jwt.sign(new RSASSASigner(testPrivateKey));
    return jwt.serialize();
  }

  private void assertSubjectlessTokenRejected(String jwt) throws Exception {
    MvcResult result =
        postAccess(VALID_USER, "DEST_VALID", "READ", jwt)
            .andExpect(status().isForbidden())
            .andReturn();
    assertFalse(result.getResponse().getContentAsString().contains("\"authorised\":true"));
  }

  private String tokenWithGlobalWritePermission(String subject) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(subject)
            .issuedAt(Instant.now().minusSeconds(1))
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("permissions", List.of("WRITE"))
            .build();
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  private JWTClaimsSet claims(String subject, String issuer, Instant expiresAt) {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .subject(subject)
        .issueTime(Date.from(Instant.now().minusSeconds(1)))
        .expirationTime(Date.from(expiresAt))
        .build();
  }

  private java.security.KeyPair generateRsaKeyPair() throws Exception {
    java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
