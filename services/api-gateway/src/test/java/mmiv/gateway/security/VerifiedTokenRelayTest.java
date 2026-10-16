package mmiv.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.handler.codec.http.HttpHeaders;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mmiv.gateway.ApiGatewayApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = ApiGatewayApplication.class,
    properties = {"eureka.client.enabled=false", "spring.cloud.loadbalancer.cache.enabled=false"})
@AutoConfigureWebTestClient
@Import(VerifiedTokenRelayTest.RelayDecoderConfiguration.class)
class VerifiedTokenRelayTest {
  private static final AtomicInteger REQUESTS = new AtomicInteger();
  private static final AtomicReference<HttpHeaders> HEADERS = new AtomicReference<>();
  private static final DisposableServer BACKEND =
      HttpServer.create()
          .host("127.0.0.1")
          .port(0)
          .handle(
              (request, response) -> {
                REQUESTS.incrementAndGet();
                HEADERS.set(request.requestHeaders().copy());
                return response.sendString(Mono.just("report"));
              })
          .bindNow();

  @Autowired private WebTestClient http;

  @DynamicPropertySource
  static void backend(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.cloud.discovery.client.simple.instances.reporting-service[0].uri",
        () -> "http://127.0.0.1:" + BACKEND.port());
  }

  @BeforeEach
  void resetBackend() {
    REQUESTS.set(0);
    HEADERS.set(null);
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.disposeNow();
  }

  @Test
  void cookieAuthenticationRelaysBearerAndRemovesSessionCookie() {
    http.get()
        .uri("/api/reports/relay-probe")
        .cookie("MMIV_SESSION", "verified-cookie")
        .exchange()
        .expectStatus()
        .isOk();

    assertEquals(List.of("Bearer verified-cookie"), HEADERS.get().getAll("Authorization"));
    assertFalse(HEADERS.get().contains("Cookie"));
  }

  @Test
  void verifiedExplicitBearerIsRelayedUnchanged() {
    http.get()
        .uri("/api/reports/relay-probe")
        .header("Authorization", "Bearer verified-bearer")
        .exchange()
        .expectStatus()
        .isOk();

    assertEquals(List.of("Bearer verified-bearer"), HEADERS.get().getAll("Authorization"));
  }

  @Test
  void spoofedIdentityAndForwardedAuthorizationHeadersAreReplaced() {
    http.get()
        .uri("/api/reports/relay-probe")
        .header("Authorization", "Bearer verified-bearer")
        .header("X-User-Id", "admin")
        .header("X-Role", "ADMIN")
        .header("X-Authenticated-User", "attacker")
        .header("Proxy-Authorization", "Bearer malicious")
        .header("X-Forwarded-Authorization", "Bearer first", "Bearer second")
        .exchange()
        .expectStatus()
        .isOk();

    HttpHeaders headers = HEADERS.get();
    assertEquals(List.of("Bearer verified-bearer"), headers.getAll("Authorization"));
    assertNull(headers.get("X-User-Id"));
    assertNull(headers.get("X-Role"));
    assertNull(headers.get("X-Authenticated-User"));
    assertNull(headers.get("Proxy-Authorization"));
    assertNull(headers.get("X-Forwarded-Authorization"));
  }

  @Test
  void unauthenticatedProtectedRequestNeverHitsBackend() {
    http.get().uri("/api/reports/relay-probe").exchange().expectStatus().isUnauthorized();
    assertEquals(0, REQUESTS.get());
  }

  @TestConfiguration
  static class RelayDecoderConfiguration {
    @Bean
    @Primary
    ReactiveJwtDecoder relayJwtDecoder() {
      return token -> {
        if (!token.startsWith("verified-")) {
          return Mono.error(new BadJwtException("invalid test token"));
        }
        return Mono.just(
            new Jwt(
                token,
                Instant.now().minusSeconds(30),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of(
                    "sub", "U001",
                    "username", "alice",
                    "role", "USER",
                    "iss", "https://auth.mmiv.example")));
      };
    }
  }
}
