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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import mmiv.gateway.ApiGatewayApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

  private static final AtomicInteger MUTATIONS = new AtomicInteger();

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

  @Test
  void postSessionExchangesBearerForSecureHttpOnlyCookie() throws Exception {
    Instant expiry = Instant.now().plusSeconds(600);
    ResponseCookie cookie = establishSession(token(ISSUER, "U001", expiry));

    org.junit.jupiter.api.Assertions.assertTrue(cookie.isHttpOnly());
    org.junit.jupiter.api.Assertions.assertFalse(cookie.isSecure());
    org.junit.jupiter.api.Assertions.assertEquals("Strict", cookie.getSameSite());
    org.junit.jupiter.api.Assertions.assertEquals("/", cookie.getPath());
    org.junit.jupiter.api.Assertions.assertTrue(cookie.getMaxAge().toSeconds() > 0);
    org.junit.jupiter.api.Assertions.assertTrue(cookie.getMaxAge().toSeconds() <= 600);
  }

  @Test
  void sessionLifetimeNeverExceedsJwtOr1800Seconds() throws Exception {
    ResponseCookie longLived =
        establishSession(token(ISSUER, "U001", Instant.now().plusSeconds(3600)));
    ResponseCookie shortLived =
        establishSession(token(ISSUER, "U001", Instant.now().plusSeconds(120)));

    org.junit.jupiter.api.Assertions.assertTrue(longLived.getMaxAge().toSeconds() <= 1800);
    org.junit.jupiter.api.Assertions.assertTrue(shortLived.getMaxAge().toSeconds() <= 120);
  }

  @Test
  void getSessionReturnsIdentityExpiryAndCsrfContract() throws Exception {
    String jwt = token(ISSUER, "U001", Instant.now().plusSeconds(600));
    Map<?, ?> session =
        http.get()
            .uri("/api/v1/session")
            .cookie("MMIV_SESSION", jwt)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();

    org.junit.jupiter.api.Assertions.assertNotNull(session);
    org.junit.jupiter.api.Assertions.assertEquals("U001", session.get("userId"));
    org.junit.jupiter.api.Assertions.assertEquals("alice", session.get("username"));
    org.junit.jupiter.api.Assertions.assertEquals("USER", session.get("role"));
    org.junit.jupiter.api.Assertions.assertEquals("X-XSRF-TOKEN", session.get("csrfHeader"));
    org.junit.jupiter.api.Assertions.assertNotNull(session.get("csrfToken"));
    org.junit.jupiter.api.Assertions.assertNotNull(session.get("expiresAt"));
    org.junit.jupiter.api.Assertions.assertTrue(
        ((Number) session.get("expiresInSeconds")).longValue() > 0);
  }

  @Test
  void deleteSessionExpiresCookie() throws Exception {
    String jwt = token(ISSUER, "U001", Instant.now().plusSeconds(600));
    CsrfSession csrf = csrfSession(jwt);

    http.delete()
        .uri("/api/v1/session")
        .cookie("MMIV_SESSION", jwt)
        .cookie("XSRF-TOKEN", csrf.cookieValue())
        .header(csrf.headerName(), csrf.token())
        .exchange()
        .expectStatus()
        .isNoContent()
        .expectCookie()
        .maxAge("MMIV_SESSION", java.time.Duration.ZERO);
  }

  @Test
  void cookieMutationWithoutCsrfIsRejectedBeforeBackend() throws Exception {
    MUTATIONS.set(0);
    String jwt = token(ISSUER, "U001", Instant.now().plusSeconds(600));

    http.post()
        .uri("/test/mutation")
        .cookie("MMIV_SESSION", jwt)
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.error")
        .isEqualTo("CSRF_INVALID");
    org.junit.jupiter.api.Assertions.assertEquals(0, MUTATIONS.get());
  }

  @Test
  void staleOrMismatchedCsrfIsRejected() throws Exception {
    MUTATIONS.set(0);
    String jwt = token(ISSUER, "U001", Instant.now().plusSeconds(600));
    CsrfSession csrf = csrfSession(jwt);

    http.post()
        .uri("/test/mutation")
        .cookie("MMIV_SESSION", jwt)
        .cookie("XSRF-TOKEN", csrf.cookieValue())
        .header(csrf.headerName(), "stale-token")
        .exchange()
        .expectStatus()
        .isForbidden();
    org.junit.jupiter.api.Assertions.assertEquals(0, MUTATIONS.get());
  }

  @Test
  void cookieMutationWithCsrfPasses() throws Exception {
    MUTATIONS.set(0);
    String jwt = token(ISSUER, "U001", Instant.now().plusSeconds(600));
    CsrfSession csrf = csrfSession(jwt);

    http.post()
        .uri("/test/mutation")
        .cookie("MMIV_SESSION", jwt)
        .cookie("XSRF-TOKEN", csrf.cookieValue())
        .header(csrf.headerName(), csrf.token())
        .exchange()
        .expectStatus()
        .isOk();
    org.junit.jupiter.api.Assertions.assertEquals(1, MUTATIONS.get());
  }

  @Test
  void explicitBearerMutationDoesNotRequireCsrf() throws Exception {
    MUTATIONS.set(0);
    String jwt = token(ISSUER, "U001", Instant.now().plusSeconds(600));

    http.post()
        .uri("/test/mutation")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
        .exchange()
        .expectStatus()
        .isOk();
    org.junit.jupiter.api.Assertions.assertEquals(1, MUTATIONS.get());
  }

  private ResponseCookie establishSession(String jwt) {
    return http.post()
        .uri("/api/v1/session")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatus.NO_CONTENT)
        .expectHeader()
        .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        .returnResult(Void.class)
        .getResponseCookies()
        .getFirst("MMIV_SESSION");
  }

  private CsrfSession csrfSession(String jwt) {
    var result =
        http.get()
            .uri("/api/v1/session")
            .cookie("MMIV_SESSION", jwt)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(Map.class)
            .returnResult();
    Map<?, ?> body = result.getResponseBody();
    ResponseCookie csrfCookie = result.getResponseCookies().getFirst("XSRF-TOKEN");
    org.junit.jupiter.api.Assertions.assertNotNull(body);
    org.junit.jupiter.api.Assertions.assertNotNull(csrfCookie);
    return new CsrfSession(
        body.get("csrfHeader").toString(), body.get("csrfToken").toString(), csrfCookie.getValue());
  }

  private record CsrfSession(String headerName, String token, String cookieValue) {}

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

    @PostMapping("/test/mutation")
    String mutation() {
      MUTATIONS.incrementAndGet();
      return "mutated";
    }
  }
}
