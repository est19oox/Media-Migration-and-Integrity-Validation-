package mmiv.user_access_service.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@Profile("!test")
public class JwtConfig {

  private final String keyId;

  public JwtConfig(@Value("${jwt.key-id}") String keyId) {
    if (keyId == null || keyId.isBlank()) {
      throw new IllegalArgumentException("JWT key ID must not be blank");
    }
    this.keyId = keyId;
  }

  @Bean
  public RSAPrivateKey rsaPrivateKey(@Value("${jwt.private-key}") Resource resource)
      throws Exception {

    String pem = resource.getContentAsString(StandardCharsets.UTF_8);
    String normalizedPem = pem.strip();

    if (!normalizedPem.startsWith("-----BEGIN PRIVATE KEY-----")
        || !normalizedPem.endsWith("-----END PRIVATE KEY-----")) {
      throw new IllegalArgumentException("Invalid RSA private key PEM format");
    }
    String base64 =
        pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");

    byte[] decoded = Base64.getDecoder().decode(base64);

    return (RSAPrivateKey)
        KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
  }

  @Bean
  public RSAPublicKey rsaPublicKey(@Value("${jwt.public-key}") Resource resource) throws Exception {

    String pem = resource.getContentAsString(StandardCharsets.UTF_8);

    String base64 =
        pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");

    byte[] decoded = Base64.getDecoder().decode(base64);

    return (RSAPublicKey)
        KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
  }

  @Bean
  public JwtEncoder jwtEncoder(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
    if (privateKey.getModulus().bitLength() < 2048 || publicKey.getModulus().bitLength() < 2048) {

      throw new IllegalArgumentException("RSA key size must be at least 2048 bits");
    }
    if (privateKey == null || publicKey == null) {
      throw new IllegalArgumentException("RSA signing keys must not be null");
    }

    try {
      byte[] challenge = "mmiv-rsa-key-validation".getBytes(StandardCharsets.UTF_8);

      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(privateKey);
      signer.update(challenge);

      byte[] signature = signer.sign();

      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(publicKey);
      verifier.update(challenge);

      if (!verifier.verify(signature)) {
        throw new IllegalArgumentException("RSA private and public keys do not match");
      }

    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalArgumentException("RSA key pair validation failed", ex);
    }

    RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();

    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
  }

  @Bean
  public JwtDecoder jwtDecoder(RSAPublicKey publicKey, @Value("${jwt.issuer}") String issuer) {

    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("JWT issuer must not be blank");
    }

    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();

    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));

    return decoder;
  }
}
