
package mmiv.user_access_service.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtConfig {

    @Bean
    public JwtEncoder jwtEncoder(
            @Value("${jwt.secret}") String secret) {

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing secret is required");
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT secret must be at least 32 bytes");
        }

        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");

        return new NimbusJwtEncoder(
                new ImmutableSecret<>(key));
    }
}
