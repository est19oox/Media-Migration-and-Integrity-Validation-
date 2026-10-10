package mmiv.user_access_service.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
// import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mmiv.user_access_service.config.JwtConfig;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class JwksControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;

  @Value("${jwt.key-id}")
  private String configuredKeyId;

  @Test
  void shouldExposePublicRsaKey() throws Exception {

    mockMvc
        .perform(get("/.well-known/jwks.json"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
        .andExpect(jsonPath("$.keys[0].kid").value(configuredKeyId))
        .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
        .andExpect(jsonPath("$.keys[0].use").value("sig"))
        .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
        .andExpect(jsonPath("$.keys[0].e").isNotEmpty())
        .andExpect(jsonPath("$.keys[0].d").doesNotExist())
        .andExpect(jsonPath("$.keys[0].p").doesNotExist())
        .andExpect(jsonPath("$.keys[0].q").doesNotExist())
        .andExpect(jsonPath("$.keys[0].dp").doesNotExist())
        .andExpect(jsonPath("$.keys[0].dq").doesNotExist())
        .andExpect(jsonPath("$.keys[0].qi").doesNotExist());
  }

  @Test
  void shouldVerifySignedJwtUsingPublishedJwks() throws Exception {

    // 1. Create an authenticated test user
    User user =
        new User("U001", "alice", "OPERATOR", true, Set.of(Permission.READ, Permission.WRITE));

    // 2. Generate a real RS256 JWT
    String token = jwtService.generateToken(user);

    // 3. Fetch the published JWKS
    String jwksJson =
        mockMvc
            .perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // 4. Parse the public key from the JWKS
    JWKSet jwkSet = JWKSet.parse(jwksJson);

    RSAKey rsaKey = (RSAKey) jwkSet.getKeys().get(0);

    assertFalse(rsaKey.isPrivate());

    // 5. Verify JWT using only the published public key
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();

    // Use a local JWK source instead of making a network request
    decoder =
        NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey())
            .signatureAlgorithm(
                org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
            .build();

    Jwt jwt = decoder.decode(token);

    // 6. Verify JWT header and claims
    assertEquals("RS256", jwt.getHeaders().get("alg"));

    assertEquals(rsaKey.getKeyID(), jwt.getHeaders().get("kid"));

    assertEquals("U001", jwt.getSubject());
    assertEquals("alice", jwt.getClaimAsString("username"));
    assertEquals("OPERATOR", jwt.getClaimAsString("role"));

    assertEquals(List.of("READ", "WRITE"), jwt.getClaimAsStringList("permissions"));
  }

  @Test
  void shouldUseConfiguredKeyIdForJwtAndJwks() throws Exception {
    KeyPair keyPair = generateKeyPair();
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
    String keyId = "configured-test-key";

    JwtEncoder encoder =
        new NimbusJwtEncoder(
            new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(
                new JWKSet(
                    new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build())));
    JwtService service = new JwtService(encoder);
    JwksController controller = new JwksController(publicKey, keyId);
    Map<?, ?> jwks = controller.jwks();
    Map<?, ?> publishedKey = (Map<?, ?>) ((List<?>) jwks.get("keys")).get(0);

    String token =
        service.generateToken(new User("U001", "alice", "OPERATOR", true, Set.of(Permission.READ)));
    Jwt jwt =
        NimbusJwtDecoder.withPublicKey(publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build()
            .decode(token);

    assertEquals(keyId, jwt.getHeaders().get("kid"));
    assertEquals(keyId, publishedKey.get("kid"));
    assertEquals("RSA", publishedKey.get("kty"));
    assertEquals("RS256", publishedKey.get("alg"));
    assertEquals("sig", publishedKey.get("use"));
    assertFalse(publishedKey.containsKey("d"));
    assertFalse(publishedKey.containsKey("p"));
    assertFalse(publishedKey.containsKey("q"));
  }

  @Test
  void shouldRejectBlankKeyId() throws Exception {
    RSAPublicKey publicKey = (RSAPublicKey) generateKeyPair().getPublic();

    assertThrows(IllegalArgumentException.class, () -> new JwksController(publicKey, "  "));
    assertThrows(IllegalArgumentException.class, () -> new JwtConfig("  "));
  }

  private static KeyPair generateKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }
}
