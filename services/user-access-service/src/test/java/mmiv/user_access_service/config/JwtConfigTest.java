package mmiv.user_access_service.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class JwtConfigTest {

  @Test
  void acceptsMatchingAtLeast2048BitKeyPair() throws Exception {
    JwtConfig config = new JwtConfig("test-key");
    KeyPair pair = keyPair(2048);
    assertDoesNotThrow(
        () ->
            JwtConfig.validateKeyPair(
                (RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic()));
    assertDoesNotThrow(
        () ->
            config.jwtEncoder((RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic()));
  }

  @Test
  void rejectsMismatchedAndWeakKeyPairs() throws Exception {
    JwtConfig config = new JwtConfig("test-key");
    KeyPair pair = keyPair(2048);
    KeyPair other = keyPair(2048);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config.jwtEncoder((RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) other.getPublic()));

    KeyPair weak = keyPair(1024);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtConfig.validateKeyPair(
                (RSAPrivateKey) weak.getPrivate(), (RSAPublicKey) weak.getPublic()));
  }

  @Test
  void rejectsNonStrictPemHeaders() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtConfig.parsePublicKey(
                "-----BEGIN RSA PUBLIC KEY-----bad" + "-----END RSA PUBLIC KEY-----"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JwtConfig.parsePrivateKey(
                "-----BEGIN RSA PRIVATE KEY-----bad" + "-----END RSA PRIVATE KEY-----"));
  }

  @Test
  void rejectsBlankKeyId() {
    assertThrows(IllegalArgumentException.class, () -> new JwtConfig(" "));
  }

  @Test
  void loadsMatchingPemFromInjectedSecretsWithoutLocalFiles() throws Exception {
    var pair = keyPair(2048);
    var config = new JwtConfig("fargate-key");
    var resources = new DefaultResourceLoader();
    String privatePem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----";
    String publicPem = "-----BEGIN PUBLIC KEY-----\n"
        + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded())
        + "\n-----END PUBLIC KEY-----";
    var privateKey = config.rsaPrivateKey("", privatePem, resources);
    var publicKey = config.rsaPublicKey("", publicPem, resources);
    assertDoesNotThrow(() -> config.jwtEncoder(privateKey, publicKey));
  }

  private static KeyPair keyPair(int size) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(size);
    return generator.generateKeyPair();
  }
}
