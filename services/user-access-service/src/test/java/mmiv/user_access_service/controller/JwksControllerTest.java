package mmiv.user_access_service.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JwksControllerTest {

  @Test
  void publishesOnlyConfiguredRsaPublicKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    RSAPublicKey publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
    JwksController controller = new JwksController(publicKey, "rotated-key");

    Map<String, Object> response = controller.jwks();

    Object keysObject = response.get("keys");

    List<?> keys = assertInstanceOf(List.class, keysObject);

    assertFalse(keys.isEmpty());

    Map<?, ?> key = assertInstanceOf(Map.class, keys.get(0));

    assertEquals("RSA", key.get("kty"));
    assertEquals("rotated-key", key.get("kid"));
    assertEquals("RS256", key.get("alg"));
    assertEquals("sig", key.get("use"));
    assertEquals("AQAB", key.get("e"));
    assertFalse(key.containsKey("d"));
    assertFalse(key.containsKey("p"));
    assertFalse(key.containsKey("q"));
    assertFalse(key.containsKey("dp"));
    assertFalse(key.containsKey("dq"));
    assertFalse(key.containsKey("qi"));
  }

  @Test
  void rejectsBlankKeyId() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    RSAPublicKey publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
    assertThrows(IllegalArgumentException.class, () -> new JwksController(publicKey, " "));
  }
}
