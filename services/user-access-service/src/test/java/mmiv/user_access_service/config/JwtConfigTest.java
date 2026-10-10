package mmiv.user_access_service.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;

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

  private static KeyPair keyPair(int size) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(size);
    return generator.generateKeyPair();
  }
}
