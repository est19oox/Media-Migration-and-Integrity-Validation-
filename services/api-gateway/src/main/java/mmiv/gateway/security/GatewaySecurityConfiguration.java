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
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
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
  ServerCsrfTokenRepository gatewayCsrfTokenRepository(
      @Value("${mmiv.auth.cookie-secure:false}") boolean secureCookie) {
    CookieServerCsrfTokenRepository repository =
        CookieServerCsrfTokenRepository.withHttpOnlyFalse();
    repository.setCookiePath("/");
    repository.setCookieCustomizer(cookie -> cookie.secure(secureCookie).sameSite("Strict"));
    return repository;
  }

  @Bean
  ServerAccessDeniedHandler gatewayAccessDeniedHandler() {
    return (exchange, exception) -> {
      byte[] body = "{\"error\":\"CSRF_INVALID\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
      exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
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
      ServerAuthenticationEntryPoint entryPoint,
      ServerCsrfTokenRepository csrfRepository,
      ServerAccessDeniedHandler accessDeniedHandler) {
    ServerCsrfTokenRequestAttributeHandler csrfHandler =
        new ServerCsrfTokenRequestAttributeHandler();
    ServerWebExchangeMatcher cookieMutation =
        exchange ->
            CsrfWebFilter.DEFAULT_CSRF_MATCHER
                .matches(exchange)
                .flatMap(
                    methodRequiresCsrf -> {
                      if (!methodRequiresCsrf.isMatch()) {
                        return ServerWebExchangeMatcher.MatchResult.notMatch();
                      }
                      boolean cookiePresent =
                          exchange.getRequest().getCookies().getFirst("MMIV_SESSION") != null;
                      boolean explicitBearer =
                          exchange
                              .getRequest()
                              .getHeaders()
                              .containsHeader(HttpHeaders.AUTHORIZATION);
                      return cookiePresent && !explicitBearer
                          ? ServerWebExchangeMatcher.MatchResult.match()
                          : ServerWebExchangeMatcher.MatchResult.notMatch();
                    });
    return http.csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(csrfHandler)
                    .requireCsrfProtectionMatcher(cookieMutation)
                    .accessDeniedHandler(accessDeniedHandler))
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers(
                        HttpMethod.GET,
                      "/actuator/health",
                      "/actuator/health/**",
                      "/actuator/info",
                      "/user-access/actuator/health",
                      "/user-access/actuator/health/**",
                      "/user-access/.well-known/jwks.json")
                    .permitAll()
                    .pathMatchers(
                        HttpMethod.GET,
                        "/login.html",
                        "/signup.html",
                        "/reset-password.html",
                        "/pages/login.html",
                        "/pages/signup.html",
                        "/pages/reset-password.html",
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
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
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
