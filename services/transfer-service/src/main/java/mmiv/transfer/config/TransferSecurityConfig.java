package mmiv.transfer.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TransferSecurityConfig {
  public static final String AUTH_COOKIE = "MMIV_SESSION";

  /** Where the new frontend's pages live, as opposed to the legacy root pages. */
  private static final String NEW_FRONTEND_ROOT = "/pages/";

  private static final String NEW_FRONTEND_LOGIN = NEW_FRONTEND_ROOT + "login.html";

  @Bean
  public JwtDecoder transferJwtDecoder(
      @Value("${mmiv.auth.jwk-set-uri}") String jwks, @Value("${mmiv.auth.issuer}") String issuer) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
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
  public BearerTokenResolver transferTokenResolver() {
    DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    return request -> {
      // An explicit Authorization header takes priority, even if it is invalid.
      if (request.getHeader("Authorization") != null) return headerResolver.resolve(request);
      if (request.getCookies() != null) {
        for (Cookie cookie : request.getCookies()) {
          if (AUTH_COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
      }
      return null;
    };
  }

  @Bean
  public AuthenticationEntryPoint transferEntryPoint() {
    return (request, response, exception) -> {
      response.setHeader("Cache-Control", "no-store");
      if (isPage(request)) {
        // Keep the same origin-relative redirect behind the gateway and direct access.
        // The new frontend signs in through its own page, so a page under /pages/ is
        // sent there; every legacy page still lands on /login.html as before.
        response.setStatus(302);
        response.setHeader("Location", loginPageFor(request));
      } else {
        response.setHeader("WWW-Authenticate", "Bearer");
        response.sendError(401, "Login required");
      }
    };
  }

  private static boolean isPage(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return "/".equals(path) || path.endsWith(".html");
  }

  /** The login page that belongs to the frontend the requested page came from. */
  private static String loginPageFor(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.startsWith(NEW_FRONTEND_ROOT) ? NEW_FRONTEND_LOGIN : "/login.html";
  }

  @Bean
  public SecurityFilterChain transferSecurityFilterChain(
      HttpSecurity http,
      BearerTokenResolver resolver,
      AuthenticationEntryPoint entryPoint,
      @Value("${mmiv.auth.cookie-secure:false}") boolean secureCookie,
      mmiv.transfer.integration.MetadataClient metadata)
      throws Exception {
    CookieCsrfTokenRepository csrfCookies = new CookieCsrfTokenRepository();
    csrfCookies.setCookiePath("/");
    csrfCookies.setCookieCustomizer(
        cookie -> cookie.httpOnly(true).sameSite("Strict").secure(secureCookie));
    return http.addFilterBefore(
            new mmiv.transfer.integration.DestinationPermissionFilter(metadata),
            org.springframework.security.web.access.intercept.AuthorizationFilter.class)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfCookies)
                    .withObjectPostProcessor(
                        new ObjectPostProcessor<CsrfFilter>() {
                          @Override
                          public <O extends CsrfFilter> O postProcess(O filter) {
                            // Resource Server automatically exempts resolved bearer tokens.
                            // Our resolver also accepts cookies, so replace that final matcher:
                            // only explicit Authorization headers bypass CSRF, never cookies.
                            filter.setRequireCsrfProtectionMatcher(
                                request ->
                                    CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                                        && request.getHeader("Authorization") == null);
                            filter.setAccessDeniedHandler(
                                (request, response, exception) -> {
                                  boolean hasCookie = false;
                                  if (request.getCookies() != null) {
                                    for (Cookie cookie : request.getCookies()) {
                                      if (AUTH_COOKIE.equals(cookie.getName())) hasCookie = true;
                                    }
                                  }
                                  response.setStatus(hasCookie ? 403 : 401);
                                  response.setHeader("Cache-Control", "no-store");
                                  response.setContentType("application/json");
                                  response
                                      .getWriter()
                                      .write(
                                          hasCookie
                                              ? "{\"error\":\"CSRF_INVALID\"}"
                                              : "{\"error\":\"LOGIN_REQUIRED\"}");
                                });
                            return filter;
                          }
                        }))
        .authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    // The new frontend's sign-in and sign-up pages, and the styles,
                    // scripts, images and fonts every new page loads, are readable
                    // without a session. /pages/** is deliberately not opened as a
                    // whole: the protected new pages are named in the list below, so
                    // anything else added under /pages/ stays closed until it is
                    // listed. Nothing here exposes data; the APIs keep their rules.
                    .requestMatchers(
                        HttpMethod.GET,
                        NEW_FRONTEND_LOGIN,
                        NEW_FRONTEND_ROOT + "signup.html",
                        "/css/**",
                        "/js/**",
                        "/img/**",
                        "/font/**",
                        "/assets/**")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/",
                        "/home.html",
                        "/upload.html",
                        "/files.html",
                        "/logs.html",
                        "/admin.html",
                        "/invite.html",
                        "/admin.js",
                        "/portal.js",
                        "/portal.css",
                        "/auth.js",
                        "/preview.js",
                        "/file-browser.js",
                        "/history.html",
                        // The new frontend's signed-in pages. Its login and signup
                        // pages are permitted above; anything else it ships stays
                        // closed until it is named here.
                        NEW_FRONTEND_ROOT + "home.html",
                        NEW_FRONTEND_ROOT + "file-manager.html",
                        NEW_FRONTEND_ROOT + "admin.html",
                        NEW_FRONTEND_ROOT + "activity.html")
                    .authenticated()
                    .requestMatchers(
                        "/api/v1/transfers",
                        "/api/v1/transfers/**",
                        "/api/v1/files",
                        "/api/v1/files/**",
                        "/api/v1/session",
                        "/api/v1/destinations",
                        "/api/v1/verification/**",
                        "/api/v1/portal/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
        .oauth2ResourceServer(
            resource ->
                resource
                    .bearerTokenResolver(resolver)
                    .authenticationEntryPoint(entryPoint)
                    .jwt(jwt -> {}))
        .build();
  }
}
