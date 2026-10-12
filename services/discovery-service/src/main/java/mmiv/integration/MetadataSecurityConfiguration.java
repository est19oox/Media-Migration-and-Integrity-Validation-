package mmiv.integration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class MetadataSecurityConfiguration {
    @Bean public JwtDecoder metadataJwtDecoder(@Value("${mmiv.auth.jwk-set-uri:http://user-access-service:8083/.well-known/jwks.json}") String jwks,
            @Value("${mmiv.auth.issuer:https://auth.mmiv.example}") String issuer) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                jwt->jwt.getExpiresAt()!=null && jwt.getSubject()!=null && !jwt.getSubject().isBlank()
                ?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));return decoder;
    }
    @Bean public SecurityFilterChain metadataSecurity(HttpSecurity http,PermissionClient permissions,
            @Value("${mmiv.metadata.destination-id:${mmiv.s3.bucket:media-migration-dev-landing}}") String destination) throws Exception {
        return http.addFilterBefore(new DiscoveryPermissionFilter(permissions,destination),org.springframework.security.web.access.intercept.AuthorizationFilter.class).csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll().requestMatchers("/actuator/health","/actuator/health/**","/actuator/info").permitAll()
                .requestMatchers("/api/discovery/**").authenticated().requestMatchers("/api/metadata/**").authenticated().anyRequest().denyAll())
            .oauth2ResourceServer(o->o.jwt(j->{})).build();
    }
}
