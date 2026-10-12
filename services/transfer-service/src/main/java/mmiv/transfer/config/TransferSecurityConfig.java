package mmiv.transfer.config;

import java.time.Instant;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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

    @Bean
    public JwtDecoder transferJwtDecoder(
            @Value("${mmiv.auth.jwk-set-uri}") String jwks,
            @Value("${mmiv.auth.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                jwt -> jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(Instant.now())
                        && jwt.getSubject() != null && !jwt.getSubject().isBlank()
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
                response.sendRedirect("/login.html");
            } else {
                response.setHeader("WWW-Authenticate", "Bearer");
                response.sendError(401, "Login required");
            }
        };
    }

    private static boolean isPage(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "/".equals(path) || "/upload.html".equals(path) || "/files.html".equals(path);
    }

    @Bean
    public SecurityFilterChain transferSecurityFilterChain(HttpSecurity http,
            BearerTokenResolver resolver, AuthenticationEntryPoint entryPoint,
            @Value("${mmiv.auth.cookie-secure:false}") boolean secureCookie, mmiv.transfer.integration.MetadataClient metadata) throws Exception {
        CookieCsrfTokenRepository csrfCookies = new CookieCsrfTokenRepository();
        csrfCookies.setCookiePath("/");
        csrfCookies.setCookieCustomizer(cookie -> cookie.httpOnly(true).sameSite("Strict").secure(secureCookie));
        return http
                .addFilterBefore(new mmiv.transfer.integration.DestinationPermissionFilter(metadata), org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfCookies)
                        .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                            @Override
                            public <O extends CsrfFilter> O postProcess(O filter) {
                                // Resource Server automatically exempts resolved bearer tokens.
                                // Our resolver also accepts cookies, so replace that final matcher:
                                // only explicit Authorization headers bypass CSRF, never cookies.
                                filter.setRequireCsrfProtectionMatcher(request ->
                                        CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                                                && request.getHeader("Authorization") == null);
                                filter.setAccessDeniedHandler((request, response, exception) -> {
                                    boolean hasCookie = false;
                                    if (request.getCookies() != null) {
                                        for (Cookie cookie : request.getCookies()) {
                                            if (AUTH_COOKIE.equals(cookie.getName())) hasCookie = true;
                                        }
                                    }
                                    response.setStatus(hasCookie ? 403 : 401);
                                    response.setHeader("Cache-Control", "no-store");
                                    response.setContentType("application/json");
                                    response.getWriter().write(hasCookie
                                            ? "{\"error\":\"CSRF_INVALID\"}"
                                            : "{\"error\":\"LOGIN_REQUIRED\"}");
                                });
                                return filter;
                            }
                        }))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/upload.html", "/files.html", "/auth.js").authenticated()
                        .requestMatchers("/api/v1/transfers", "/api/v1/transfers/**",
                                "/api/v1/files", "/api/v1/files/**", "/api/v1/session", "/api/v1/destinations").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(resource -> resource.bearerTokenResolver(resolver)
                        .authenticationEntryPoint(entryPoint).jwt(jwt -> {}))
                .build();
    }
}
