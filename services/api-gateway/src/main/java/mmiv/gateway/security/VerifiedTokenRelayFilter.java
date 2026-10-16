package mmiv.gateway.security;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class VerifiedTokenRelayFilter implements GlobalFilter, Ordered {
  private static final String SESSION_COOKIE = "MMIV_SESSION";
  private static final List<String> UNTRUSTED_HEADERS =
      List.of(
          "X-User-Id",
          "X-Role",
          "X-Authenticated-User",
          "X-Forwarded-User",
          "X-Forwarded-Email",
          "Proxy-Authorization",
          "X-Forwarded-Authorization");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return exchange
        .getPrincipal()
        .ofType(JwtAuthenticationToken.class)
        .map(
            authentication -> {
              String token = authentication.getToken().getTokenValue();
              String downstreamCookies =
                  exchange.getRequest().getCookies().values().stream()
                      .flatMap(List::stream)
                      .filter(cookie -> !SESSION_COOKIE.equals(cookie.getName()))
                      .map(Object::toString)
                      .collect(Collectors.joining("; "));
              var request =
                  exchange
                      .getRequest()
                      .mutate()
                      .headers(
                          headers -> {
                            headers.remove(HttpHeaders.AUTHORIZATION);
                            headers.remove(HttpHeaders.COOKIE);
                            UNTRUSTED_HEADERS.forEach(headers::remove);
                            headers.setBearerAuth(token);
                            if (!downstreamCookies.isBlank()) {
                              headers.set(HttpHeaders.COOKIE, downstreamCookies);
                            }
                          })
                      .build();
              return exchange.mutate().request(request).build();
            })
        .defaultIfEmpty(exchange)
        .flatMap(chain::filter);
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
