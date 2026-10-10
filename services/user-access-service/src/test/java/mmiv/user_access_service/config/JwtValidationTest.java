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
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class JwtValidationTest {

  @Test
  void shouldRejectJwtWithIncorrectIssuer() throws Exception {

    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);

    KeyPair pair = generator.generateKeyPair();

    RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();

    RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();

    RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("test-key").build();

    JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));

    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("https://untrusted.example")
            .subject("U001")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(1800))
            .build();

    String token =
        encoder
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims))
            .getTokenValue();

    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();

    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://auth.mmiv.example"));

    assertThrows(JwtException.class, () -> decoder.decode(token));
  }

  @Test
  void shouldAcceptJwtWithCorrectIssuer() throws Exception {

    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");

    generator.initialize(2048);

    KeyPair pair = generator.generateKeyPair();

    RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();

    RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();

    RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("test-key").build();

    JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));

    Instant now = Instant.now();

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("https://auth.mmiv.example")
            .subject("U001")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(1800))
            .build();

    String token =
        encoder
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims))
            .getTokenValue();

    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();

    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://auth.mmiv.example"));

    Jwt jwt = decoder.decode(token);

    assertEquals("U001", jwt.getSubject());
  }
}
