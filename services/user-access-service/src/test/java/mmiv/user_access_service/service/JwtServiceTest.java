package mmiv.user_access_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mmiv.user_access_service.controller.JwksController;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtServiceTest {

  private JwtService service;
  private JwtEncoder encoder;
  private JwtDecoder decoder;
  private RSAPublicKey publicKey;

  @BeforeEach
  void setUp() throws Exception {
    KeyPair keyPair = generateKeyPair(2048);
    publicKey = (RSAPublicKey) keyPair.getPublic();
    RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
    RSAKey signingKey =
        new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("test-key").build();
    encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    decoder =
        NimbusJwtDecoder.withPublicKey(publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    service = new JwtService(encoder, "https://auth.mmiv.example");
  }

  @Test
  void generatesRs256TokenWithExpectedClaims() {
    User user = new User("U001", "alice", "OPERATOR", true, Set.of(Permission.WRITE));

    Jwt jwt = decoder.decode(service.generateToken(user));

    assertEquals("RS256", jwt.getHeaders().get("alg"));
    assertEquals("test-key", jwt.getHeaders().get("kid"));
    assertEquals("U001", jwt.getSubject());
    assertEquals("alice", jwt.getClaimAsString("username"));
    assertEquals("OPERATOR", jwt.getClaimAsString("role"));
    assertEquals(List.of("WRITE"), jwt.getClaimAsStringList("permissions"));
    assertEquals(30, ChronoUnit.MINUTES.between(jwt.getIssuedAt(), jwt.getExpiresAt()));
  }

  @Test
  void tokenHeaderKidMatchesPublishedJwksKid() {
    User user = new User("U001", "alice", "OPERATOR", true, Set.of());
    Jwt jwt = decoder.decode(service.generateToken(user));
    String tokenKid = (String) jwt.getHeaders().get("kid");
    Map<String, Object> jwks = new JwksController(publicKey, "test-key").jwks();
    assertEquals(tokenKid, ((Map<?, ?>) ((List<?>) jwks.get("keys")).get(0)).get("kid"));
  }

  @Test
  void rejectsInactiveAndNullUsers() {
    User inactive = new User("U001", "alice", "OPERATOR", false, Set.of());
    assertThrows(IllegalArgumentException.class, () -> service.generateToken(inactive));
    assertThrows(IllegalArgumentException.class, () -> service.generateToken(null));
  }

  @Test
  void rejectsTamperedToken() {
    User user = new User("U001", "alice", "OPERATOR", true, Set.of());
    String[] parts = service.generateToken(user).split("\\.");
    parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
    assertThrows(JwtException.class, () -> decoder.decode(String.join(".", parts)));
  }

  @Test
  void rejectsExpiredToken() {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("https://auth.mmiv.example")
            .subject("U001")
            .issuedAt(now.minus(2, ChronoUnit.HOURS))
            .expiresAt(now.minus(1, ChronoUnit.HOURS))
            .build();
    String token =
        encoder
            .encode(
                JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
            .getTokenValue();
    assertThrows(JwtException.class, () -> decoder.decode(token));
    assertNotNull(token);
  }

  private static KeyPair generateKeyPair(int size) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(size);
    return generator.generateKeyPair();
  }
}
