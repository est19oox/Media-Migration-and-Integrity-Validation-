package mmiv.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies the bearer tokens issued by the User Access Service.
 *
 * <p>The User Access Service signs tokens with RS256 and publishes the public
 * signing key through its JWKS endpoint, so this service only needs that
 * endpoint and the expected issuer. Private key material is never required here.
 *
 * <p>{@link NimbusJwtDecoder#withJwkSetUri(String)} resolves the key set lazily
 * on the first verification instead of at startup, so a short outage of the
 * User Access JWKS endpoint delays the first authenticated request rather than
 * preventing this service from booting. Key rotation needs no change here.
 *
 * <p>Verification enforces three things:
 * <ul>
 *   <li>the RS256 signature, against the published JWKS for the token's
 *       {@code kid};</li>
 *   <li>the registered timestamp claims ({@code exp}, {@code nbf});</li>
 *   <li>the {@code iss} claim, which must equal the configured issuer.</li>
 * </ul>
 *
 * <p>Discovery verifies identity only. Destination authorization is not part of
 * this configuration.
 */
@Configuration
public class JwtConfig {

    private final String issuer;
    private final String jwkSetUri;

    public JwtConfig(
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.jwk-set-uri}") String jwkSetUri) {

        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT issuer must not be blank"
            );
        }

        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT JWK set URI must not be blank"
            );
        }

        this.issuer = issuer;
        this.jwkSetUri = jwkSetUri;
    }

    /**
     * Decodes and verifies bearer tokens. The default validator chain checks the
     * registered timestamp claims and, through {@code createDefaultWithIssuer},
     * requires the configured issuer.
     */
    @Bean
    public JwtDecoder jwtDecoder() {

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(jwkSetUri)
                .build();

        decoder.setJwtValidator(
                JwtValidators.createDefaultWithIssuer(issuer)
        );

        return decoder;
    }
}
