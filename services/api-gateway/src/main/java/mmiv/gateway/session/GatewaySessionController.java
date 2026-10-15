package mmiv.gateway.session;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/session")
public class GatewaySessionController {
  private static final Duration MAX_SESSION_LIFETIME = Duration.ofMinutes(30);
  private final boolean secureCookie;

  public GatewaySessionController(@Value("${mmiv.auth.cookie-secure:false}") boolean secureCookie) {
    this.secureCookie = secureCookie;
  }

  @PostMapping
  public Mono<ResponseEntity<Void>> establish(@AuthenticationPrincipal Jwt jwt) {
    Duration lifetime = remainingLifetime(jwt);
    ResponseCookie cookie = sessionCookie(jwt.getTokenValue(), lifetime);
    return Mono.just(
        ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .build());
  }

  @GetMapping
  public Mono<ResponseEntity<Map<String, Object>>> current(
      @AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
    return csrfToken(exchange)
        .map(
            csrf -> {
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("userId", jwt.getSubject());
              body.put("username", jwt.getClaimAsString("username"));
              body.put("role", jwt.getClaimAsString("role"));
              body.put("csrfHeader", csrf.getHeaderName());
              body.put("csrfToken", csrf.getToken());
              body.put("expiresAt", jwt.getExpiresAt().truncatedTo(ChronoUnit.SECONDS).toString());
              body.put("expiresInSeconds", remainingLifetime(jwt).toSeconds());
              return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
            });
  }

  @DeleteMapping
  public Mono<ResponseEntity<Void>> logout() {
    ResponseCookie cookie = sessionCookie("", Duration.ZERO);
    return Mono.just(
        ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .build());
  }

  private ResponseCookie sessionCookie(String value, Duration lifetime) {
    return ResponseCookie.from("MMIV_SESSION", value)
        .httpOnly(true)
        .secure(secureCookie)
        .sameSite("Strict")
        .path("/")
        .maxAge(lifetime)
        .build();
  }

  private static Duration remainingLifetime(Jwt jwt) {
    Duration remaining = Duration.between(Instant.now(), jwt.getExpiresAt());
    if (remaining.isNegative() || remaining.isZero()) {
      return Duration.ZERO;
    }
    return remaining.compareTo(MAX_SESSION_LIFETIME) > 0 ? MAX_SESSION_LIFETIME : remaining;
  }

  @SuppressWarnings("unchecked")
  private static Mono<CsrfToken> csrfToken(ServerWebExchange exchange) {
    Object attribute = exchange.getAttribute(CsrfToken.class.getName());
    if (attribute instanceof Mono<?> token) {
      return (Mono<CsrfToken>) token;
    }
    return Mono.justOrEmpty((CsrfToken) attribute);
  }
}
