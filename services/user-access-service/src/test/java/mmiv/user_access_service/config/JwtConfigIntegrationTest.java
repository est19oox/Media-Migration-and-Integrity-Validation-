package mmiv.user_access_service.config;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

class JwtConfigIntegrationTest {

  private JwtDecoder decoder;
  private JwtEncoder encoder;

  private static final String TRUSTED_ISSUER = "https://auth.mmiv.example";

  @BeforeEach
  void setUp() throws Exception {

    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");

    generator.initialize(2048);

    KeyPair pair = generator.generateKeyPair();

    RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();

    RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();

    // Use the real production configuration method
    JwtConfig config = new JwtConfig("mmiv-user-access-key-1");

    decoder = config.jwtDecoder(publicKey, TRUSTED_ISSUER);

    RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("test-key").build();

    encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
  }

  private String createToken(String issuer, Instant issuedAt, Instant expiresAt) {

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("U001")
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();

    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims))
        .getTokenValue();
  }

  @Test
  void shouldAcceptValidJwt() {

    Instant now = Instant.now();

    String token = createToken(TRUSTED_ISSUER, now, now.plusSeconds(1800));

    Jwt jwt = decoder.decode(token);

    assertEquals("U001", jwt.getSubject());
    assertEquals(TRUSTED_ISSUER, jwt.getIssuer().toString());
  }

  @Test
  void shouldRejectIncorrectIssuer() {

    Instant now = Instant.now();

    String token = createToken("https://untrusted.example", now, now.plusSeconds(1800));

    assertThrows(JwtException.class, () -> decoder.decode(token));
  }

  @Test
  void shouldRejectExpiredJwt() {

    Instant now = Instant.now();

    String token = createToken(TRUSTED_ISSUER, now.minusSeconds(3600), now.minusSeconds(600));

    assertThrows(JwtException.class, () -> decoder.decode(token));
  }
}
