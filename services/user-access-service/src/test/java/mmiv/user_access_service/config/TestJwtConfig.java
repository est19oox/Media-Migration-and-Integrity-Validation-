package mmiv.user_access_service.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@TestConfiguration
@Profile("test")
public class TestJwtConfig {

    private final String keyId;

    public TestJwtConfig(@Value("${jwt.key-id}") String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("JWT key ID must not be blank");
        }
        this.keyId = keyId;
    }

    @Bean
    public KeyPair testRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");

        generator.initialize(3072);

        return generator.generateKeyPair();
    }

    @Bean
    public RSAPrivateKey testPrivateKey(KeyPair testRsaKeyPair) {
        return (RSAPrivateKey) testRsaKeyPair.getPrivate();
    }

    @Bean
    public RSAPublicKey testPublicKey(KeyPair testRsaKeyPair) {
        return (RSAPublicKey) testRsaKeyPair.getPublic();
    }

    @Bean
    public JwtEncoder testJwtEncoder(
            RSAPrivateKey privateKey,
            RSAPublicKey publicKey) {

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(keyId)
                .build();

        return new NimbusJwtEncoder(
                new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    @Bean
    public JwtDecoder testJwtDecoder(RSAPublicKey publicKey) {
        return NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }
}