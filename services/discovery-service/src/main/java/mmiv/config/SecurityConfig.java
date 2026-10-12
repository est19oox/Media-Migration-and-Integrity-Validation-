package mmiv.config;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token security for the Discovery Service.
 *
 * <p>Every Discovery REST API requires a valid User Access Service token. The
 * public surface is deliberately limited to the health endpoints that Docker
 * health checks, the release verification scripts and the API Gateway depend
 * on. Anything not listed is denied, so new endpoints are protected by default.
 *
 * <p>No authorization rules are applied here: this configuration establishes
 * who the caller is, not what they may do.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Container health checks and release verification.
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // Spring MVC renders error dispatches separately from the
                        // request that caused them; those dispatches must not be
                        // mistaken for a new protected request.
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults()))
                .build();
    }
}
