package mmiv.gateway.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
public class GatewaySecurityConfiguration {
  @Bean
  ReactiveJwtDecoder gatewayJwtDecoder(
      @Value("${mmiv.auth.jwk-set-uri}") String jwks, @Value("${mmiv.auth.issuer}") String issuer) {
    NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            jwt ->
                jwt.getExpiresAt() != null
                        && jwt.getExpiresAt().isAfter(Instant.now())
                        && jwt.getSubject() != null
                        && !jwt.getSubject().isBlank()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    return decoder;
  }

  @Bean
  ServerAuthenticationConverter gatewayBearerTokenConverter() {
    return new CookieServerBearerTokenConverter();
  }

  @Bean
  ServerAuthenticationEntryPoint gatewayAuthenticationEntryPoint() {
    return (exchange, exception) -> {
      exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
      if (isHtmlPage(exchange)) {
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().set(HttpHeaders.LOCATION, "/pages/login.html");
        return exchange.getResponse().setComplete();
      }
      byte[] body = "{\"error\":\"LOGIN_REQUIRED\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
      return exchange
          .getResponse()
          .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    };
  }

  @Bean
  SecurityWebFilterChain gatewaySecurityWebFilterChain(
      ServerHttpSecurity http,
      ReactiveJwtDecoder decoder,
      ServerAuthenticationConverter converter,
      ServerAuthenticationEntryPoint entryPoint) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers(
                        HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .pathMatchers(
                        HttpMethod.GET,
                        "/login.html",
                        "/signup.html",
                        "/reset-password.html",
                        "/pages/login.html",
                        "/pages/signup.html",
                        "/pages/reset-password.html",
                        "/auth.js",
                        "/css/**",
                        "/js/**",
                        "/img/**",
                        "/font/**",
                        "/assets/**")
                    .permitAll()
                    .pathMatchers(
                        HttpMethod.POST,
                        "/user-access/api/auth/login",
                        "/user-access/api/auth/signup",
                        "/user-access/api/auth/password-reset")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
        .oauth2ResourceServer(
            resource ->
                resource
                    .authenticationEntryPoint(entryPoint)
                    .bearerTokenConverter(converter)
                    .jwt(jwt -> jwt.jwtDecoder(decoder)))
        .build();
  }

  private static boolean isHtmlPage(ServerWebExchange exchange) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    return "/".equals(path) || path.endsWith(".html");
  }
}
