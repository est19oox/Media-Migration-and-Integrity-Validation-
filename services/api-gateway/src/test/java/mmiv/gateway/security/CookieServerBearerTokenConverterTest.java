package mmiv.gateway.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class CookieServerBearerTokenConverterTest {
  private final CookieServerBearerTokenConverter converter = new CookieServerBearerTokenConverter();

  @Test
  void staleSessionCookieDoesNotAuthenticatePublicCredentialPosts() {
    for (String endpoint : new String[] {"login", "signup", "password-reset"}) {
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.post("/user-access/api/auth/" + endpoint)
                  .cookie(
                      new org.springframework.http.HttpCookie("MMIV_SESSION", "expired-token")));
      assertTrue(GatewaySecurityConfiguration.isPublicAuthPost(exchange), endpoint);
      assertTrue(converter.convert(exchange).blockOptional().isEmpty(), endpoint);
    }
  }

  @Test
  void sessionCookieStillAuthenticatesProtectedPosts() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/transfers")
                .cookie(new org.springframework.http.HttpCookie("MMIV_SESSION", "session-token")));
    assertTrue(!GatewaySecurityConfiguration.isPublicAuthPost(exchange));
    assertTrue(converter.convert(exchange).blockOptional().isPresent());
  }
}
