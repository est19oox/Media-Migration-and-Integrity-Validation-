package mmiv.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import mmiv.gateway.ApiGatewayApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {ApiGatewayApplication.class, GatewaySecurityIntegrationTest.Probe.class},
    properties = {"eureka.client.enabled=false", "spring.cloud.loadbalancer.cache.enabled=false"})
@AutoConfigureWebTestClient
class GatewaySecurityIntegrationTest {
  private static final String ISSUER = "https://auth.mmiv.example";
  private static final KeyPair KEYS;
  private static final HttpServer JWKS;

  static {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KEYS = generator.generateKeyPair();
      RSAKey publicKey =
          new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).keyID("gateway-test-key").build();
      byte[] body = new JWKSet(publicKey).toString().getBytes(StandardCharsets.UTF_8);
      JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      JWKS.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      JWKS.start();
    } catch (Exception error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @Autowired private WebTestClient http;

  @DynamicPropertySource
  static void authenticationProperties(DynamicPropertyRegistry registry) {
    registry.add("mmiv.auth.issuer", () -> ISSUER);
    registry.add(
        "mmiv.auth.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
  }

  @AfterAll
  static void stopJwks() {
    JWKS.stop(0);
  }

  @Test
  void validCookieAuthenticatesProtectedPage() throws Exception {
    http.get()
        .uri("/test/protected")
        .cookie("MMIV_SESSION", token(ISSUER, "U001", Instant.now().plusSeconds(300)))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("protected");
  }

  @Test
  void missingCredentialReturns401ForProtectedApi() {
    http.get().uri("/api/reports/mine").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void invalidExplicitHeaderDoesNotFallBackToValidCookie() throws Exception {
    http.get()
        .uri("/test/protected")
        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
        .cookie("MMIV_SESSION", token(ISSUER, "U001", Instant.now().plusSeconds(300)))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void expiredWrongIssuerAndBlankSubjectTokensAreRejected() throws Exception {
    for (String jwt :
        new String[] {
          token(ISSUER, "U001", Instant.now().minusSeconds(1)),
          token("https://wrong.example", "U001", Instant.now().plusSeconds(300)),
          token(ISSUER, "", Instant.now().plusSeconds(300))
        }) {
      http.get()
          .uri("/test/protected")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
          .exchange()
          .expectStatus()
          .isUnauthorized();
    }
  }

  @Test
  void loginPageAndItsAssetsRemainPublic() {
    for (String path : new String[] {"/pages/login.html", "/css/style.css", "/js/login.js"}) {
      http.get().uri(path).exchange().expectStatus().isOk();
    }
  }

  private static String token(String issuer, String subject, Instant expiry) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("gateway-test-key").build(),
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .claim("username", "alice")
                .claim("role", "USER")
                .issueTime(Date.from(Instant.now().minusSeconds(30)))
                .expirationTime(Date.from(expiry))
                .build());
    jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
    return jwt.serialize();
  }

  @RestController
  static class Probe {
    @GetMapping("/test/protected")
    String protectedPage() {
      return "protected";
    }

    @GetMapping({"/pages/login.html", "/css/style.css", "/js/login.js"})
    String publicAsset() {
      return "public";
    }
  }
}
