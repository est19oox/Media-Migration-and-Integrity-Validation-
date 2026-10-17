package mmiv.gateway;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"eureka.client.enabled=false", "spring.cloud.loadbalancer.cache.enabled=false"})
@AutoConfigureWebTestClient
@Import(FrontendOwnershipTest.FrontendDecoderConfiguration.class)
class FrontendOwnershipTest {
  @Autowired private WebTestClient http;

  @Test
  void gatewayServesPublicFrontendResourcesLocally() {
    for (String path :
        new String[] {
          "/pages/login.html",
          "/pages/reset-password.html",
          "/css/style.css",
          "/js/mmiv-auth.js",
          "/assets/particles-config.json"
        }) {
      byte[] body =
          http.get()
              .uri(path)
              .exchange()
              .expectStatus()
              .isOk()
              .expectHeader()
              .doesNotExist("X-Test-Backend")
              .expectBody()
              .returnResult()
              .getResponseBody();
      assertTrue(body != null && body.length > 0, path);
    }
    for (String path : new String[] {"/img/bg.jpg", "/font/Bender.woff"}) {
      http.get()
          .uri(path)
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .doesNotExist("X-Test-Backend");
    }
  }

  @Test
  void gatewayServesProtectedFrontendPagesLocally() {
    for (String path : new String[] {"/pages/invite.html", "/pages/home.html"}) {
      authenticated()
          .get()
          .uri(path)
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .doesNotExist("X-Test-Backend");
    }
  }

  @Test
  void rootResolvesToNewHomePage() {
    authenticated()
        .get()
        .uri("/")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(body -> assertTrue(body.contains("id=\"root\"") && body.contains("/assets/")));
  }

  @Test
  void legacyEntryPointsRedirectToNewPages() {
    assertRedirect("/login.html", "/pages/login.html", false);
    assertRedirect("/signup.html", "/pages/signup.html", false);
    assertRedirect("/reset-password.html", "/pages/reset-password.html", false);
    assertRedirect("/home.html", "/pages/home.html", true);
    assertRedirect("/upload.html", "/pages/file-manager.html", true);
    assertRedirect("/invite.html", "/pages/invite.html", true);
  }

  @Test
  void legacyWorkspaceAndUnknownFrontendPagesAreAbsent() {
    for (String path : new String[] {"/pages/not-real.html"}) {
      authenticated().get().uri(path).exchange().expectStatus().isNotFound();
    }
  }

  private void assertRedirect(String from, String to, boolean authenticationRequired) {
    WebTestClient client = authenticationRequired ? authenticated() : http;
    client
        .get()
        .uri(from)
        .exchange()
        .expectStatus()
        .is3xxRedirection()
        .expectHeader()
        .valueEquals(HttpHeaders.LOCATION, to);
  }

  private WebTestClient authenticated() {
    return http.mutate()
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer frontend-test-token")
        .build();
  }

  @TestConfiguration
  static class FrontendDecoderConfiguration {
    @Bean
    @Primary
    ReactiveJwtDecoder frontendJwtDecoder() {
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
}
