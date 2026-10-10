package mmiv.user_access_service.config;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class JwtDecoderIntegrationTest {

  @Autowired private JwtDecoder decoder;

  @Autowired private RSAPrivateKey privateKey;

  @Autowired private RSAPublicKey publicKey;

  private String createToken(String issuer) {

    RSAKey rsaKey =
        new RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyID("mmiv-user-access-key-1")
            .build();

    JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));

    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("U001")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(1800))
            .build();

    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("mmiv-user-access-key-1").build(),
                claims))
        .getTokenValue();
  }

  @Test
  void shouldAcceptJwtWithTrustedIssuer() {

    String token = createToken("https://auth.mmiv.example");

    Jwt jwt = decoder.decode(token);

    assertEquals("U001", jwt.getSubject());
  }

  @Test
  void shouldRejectJwtWithUntrustedIssuer() {

    String token = createToken("https://untrusted.example");

    assertThrows(JwtException.class, () -> decoder.decode(token));
  }

  @Test
  void shouldRejectExpiredJwt() {

    RSAKey rsaKey =
        new RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyID("mmiv-user-access-key-1")
            .build();

    JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));

    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("https://auth.mmiv.example")
            .subject("U001")
            .issuedAt(now.minusSeconds(3600))
            .expiresAt(now.minusSeconds(600))
            .build();

    String token =
        encoder
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256)
                        .keyId("mmiv-user-access-key-1")
                        .build(),
                    claims))
            .getTokenValue();

    assertThrows(JwtException.class, () -> decoder.decode(token));
  }

  @Test
  void shouldRejectJwtSignedWithDifferentRsaKey() throws Exception {

    java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");

    generator.initialize(2048);

    java.security.KeyPair wrongPair = generator.generateKeyPair();

    RSAPublicKey wrongPublicKey = (RSAPublicKey) wrongPair.getPublic();

    RSAPrivateKey wrongPrivateKey = (RSAPrivateKey) wrongPair.getPrivate();

    RSAKey wrongRsaKey =
        new RSAKey.Builder(wrongPublicKey)
            .privateKey(wrongPrivateKey)
            .keyID("mmiv-user-access-key-1")
            .build();

    JwtEncoder wrongEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(wrongRsaKey)));

    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("https://auth.mmiv.example")
            .subject("U001")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(1800))
            .build();

    String token =
        wrongEncoder
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256)
                        .keyId("mmiv-user-access-key-1")
                        .build(),
                    claims))
            .getTokenValue();

    assertThrows(JwtException.class, () -> decoder.decode(token));
  }

  @Test
  void shouldRejectHs256Token() {

    byte[] secretBytes =
        "mmiv-test-hmac-secret-32-bytes-minimum-2026".getBytes(StandardCharsets.UTF_8);

    SecretKey secretKey = new SecretKeySpec(secretBytes, "HmacSHA256");

    JwtEncoder hmacEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));

    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("https://auth.mmiv.example")
            .subject("U001")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(1800))
            .build();

    String token =
        hmacEncoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();

    assertThrows(JwtException.class, () -> decoder.decode(token));
  }
}
