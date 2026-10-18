package mmiv.gateway.security;

import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Resolves an explicit bearer header before the browser's HttpOnly session cookie. */
public final class CookieServerBearerTokenConverter implements ServerAuthenticationConverter {
  public static final String AUTH_COOKIE = "MMIV_SESSION";

  private final ServerBearerTokenAuthenticationConverter headerConverter =
      new ServerBearerTokenAuthenticationConverter();

  @Override
  public Mono<Authentication> convert(ServerWebExchange exchange) {
    String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authorization != null) {
      return headerConverter.convert(exchange);
    }
    // Login, signup and password reset must work even when the browser still
    // carries a session cookie invalidated by a password change.
    if (GatewaySecurityConfiguration.isPublicAuthPost(exchange)) {
      return Mono.empty();
    }
    HttpCookie cookie = exchange.getRequest().getCookies().getFirst(AUTH_COOKIE);
    return cookie == null || cookie.getValue().isBlank()
        ? Mono.empty()
        : Mono.just(new BearerTokenAuthenticationToken(cookie.getValue()));
  }
}
