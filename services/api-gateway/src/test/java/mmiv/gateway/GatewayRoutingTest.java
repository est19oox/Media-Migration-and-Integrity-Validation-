package mmiv.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
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
    properties = {"eureka.client.enabled=false", "spring.cloud.loadbalancer.cache.enabled=false"})
@AutoConfigureWebTestClient
@Import(GatewayRoutingTest.RoutingSecurityTestConfiguration.class)
class GatewayRoutingTest {
  static DisposableServer backend(String name) {
    return HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle(
            (request, response) -> {
              response.header("X-Test-Backend", name);
              response.header("X-Test-Path", request.uri());
              response.header(
                  "X-Test-Authorization",
                  request.requestHeaders().get(HttpHeaders.AUTHORIZATION, ""));
              if (request.method().name().equals("POST")) {
                return response.sendString(request.receive().aggregate().asString());
              }
              return response.sendString(reactor.core.publisher.Mono.just(name));
            })
        .bindNow();
  }

  static final DisposableServer first = backend("A");
  static final DisposableServer second = backend("B");
  @Autowired WebTestClient http;

  private WebTestClient authenticated() {
    return http.mutate()
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer routing-test-token")
        .build();
  }

  @TestConfiguration
  static class RoutingSecurityTestConfiguration {
    @Bean
    @Primary
    ReactiveJwtDecoder routingJwtDecoder() {
      return token ->
          Mono.just(
              new Jwt(
                  token,
                  Instant.now().minusSeconds(30),
                  Instant.now().plusSeconds(300),
                  Map.of("alg", "RS256"),
                  Map.of("sub", "U001", "iss", "https://auth.mmiv.example")));
    }
  }

  @DynamicPropertySource
  static void instances(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.cloud.discovery.client.simple.instances.transfer-service[0].uri",
        () -> "http://127.0.0.1:" + first.port());
    registry.add(
        "spring.cloud.discovery.client.simple.instances.transfer-service[1].uri",
        () -> "http://127.0.0.1:" + second.port());
    registry.add(
        "spring.cloud.discovery.client.simple.instances.integrity-service[0].uri",
        () -> "http://127.0.0.1:" + first.port());
    registry.add(
        "spring.cloud.discovery.client.simple.instances.user-access-service[0].uri",
        () -> "http://127.0.0.1:" + first.port());
    registry.add(
        "spring.cloud.discovery.client.simple.instances.reporting-service[0].uri",
        () -> "http://127.0.0.1:" + first.port());
    registry.add(
        "spring.cloud.discovery.client.simple.instances.discovery-service[0].uri",
        () -> "http://127.0.0.1:" + first.port());
  }

  @AfterAll
  static void stopServers() {
    first.disposeNow();
    second.disposeNow();
  }

  @Test
  void distributesRequestsAcrossBothInstancesAndPreservesPaths() {
    Set<String> selected = new HashSet<>();
    for (int i = 0; i < 6; i++) {
      var result =
          authenticated()
              .get()
              .uri("/api/v1/files/example/content")
              .exchange()
              .expectStatus()
              .isOk()
              .expectHeader()
              .valueEquals("X-Test-Path", "/api/v1/files/example/content")
              .expectBody(String.class)
              .returnResult();
      selected.add(result.getResponseBody());
    }
    assertEquals(Set.of("A", "B"), selected);
  }

  @Test
  void forwardsMultipartBytesAndNestedRelativePaths() {
    var parts = new MultipartBodyBuilder();
    parts.part(
        "files",
        new ByteArrayResource("local bytes".getBytes()) {
          @Override
          public String getFilename() {
            return "notes.txt";
          }
        });
    parts.part("relativePaths", "album/subfolder/notes.txt");
    var result =
        authenticated()
            .post()
            .uri("/api/v1/transfers/folder")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(parts.build())
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("X-Test-Path", "/api/v1/transfers/folder")
            .expectBody(String.class)
            .returnResult();
    assertNotNull(result.getResponseBody());
    assertTrue(result.getResponseBody().contains("local bytes"));
    assertTrue(result.getResponseBody().contains("album/subfolder/notes.txt"));
  }

  @Test
  void forwardsNativeMetadataAndReportingContracts() {
    for (String path :
        new String[] {
          "/api/metadata/plans/123",
          "/api/discovery/scan",
          "/api/reports/123",
          "/api/admin/reports/123",
          "/api/history",
          "/api/dashboard",
          "/api/integrity/validate"
        }) {
      authenticated()
          .get()
          .uri(path)
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .valueEquals("X-Test-Path", path);
    }
    authenticated()
        .get()
        .uri("/api/v1/destinations")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Test-Path", "/api/v1/destinations");
  }

  @Test
  void routesDirectProfileHistoryAndDashboardReadsWithVerifiedBearerToken() {
    for (String path : new String[] {"/api/access/me", "/api/history", "/api/dashboard"}) {
      authenticated()
          .get()
          .uri(path)
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .valueEquals("X-Test-Path", path)
          .expectHeader()
          .valueEquals("X-Test-Authorization", "Bearer routing-test-token");
    }
  }

  @Test
  void routesEveryEnabledService() {
    for (String prefix : new String[] {"integrity", "user-access", "reporting", "discovery"}) {
      authenticated()
          .get()
          .uri("/" + prefix + "/actuator/health")
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .valueEquals("X-Test-Path", "/actuator/health");
    }
    authenticated()
        .get()
        .uri("/api/access/example")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Test-Path", "/api/access/example");
    authenticated()
        .get()
        .uri("/api/v1/portal/me")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueMatches("X-Test-Backend", "[AB]")
        .expectHeader()
        .valueEquals("X-Test-Path", "/api/v1/portal/me");
    for (String action : new String[] {"login", "signup"}) {
      http.post()
          .uri("/user-access/api/auth/" + action)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{}")
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .valueEquals("X-Test-Backend", "A")
          .expectHeader()
          .valueEquals("X-Test-Path", "/api/auth/" + action);
    }
    authenticated().get().uri("/eureka/apps").exchange().expectStatus().isNotFound();
  }

  @Test
  void exposesItsOwnHealth() {
    http.get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");
  }
}
