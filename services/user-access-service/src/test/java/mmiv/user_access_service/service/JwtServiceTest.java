package mmiv.user_access_service.service;

import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import java.time.temporal.ChronoUnit;

import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

        private JwtService jwtService;
        private JwtDecoder decoder;
        private JwtEncoder encoder;
        @BeforeEach
        void setUp() {

                byte[] keyBytes = ("mmiv-test-secret-key-32-bytes-minimum-2026").getBytes(StandardCharsets.UTF_8);

                SecretKey secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");

                encoder = new NimbusJwtEncoder(
                                new ImmutableSecret<>(secretKey));

                decoder = NimbusJwtDecoder
                                .withSecretKey(secretKey)
                                .macAlgorithm(MacAlgorithm.HS256)
                                .build();

                jwtService = new JwtService(encoder);
        }

        @Test
        void shouldGenerateValidTokenForActiveUser() {

                User user = new User(
                                "U001",
                                "alice",
                                "OPERATOR",
                                true,
                                Set.of(Permission.READ, Permission.WRITE));

                String token = jwtService.generateToken(user);

                assertNotNull(token);
                assertFalse(token.isBlank());

                Jwt jwt = decoder.decode(token);

                assertEquals("U001", jwt.getSubject());
                assertEquals("https://auth.mmiv.example", jwt.getIssuer().toString());
                assertEquals("alice", jwt.getClaimAsString("username"));
                assertEquals("OPERATOR", jwt.getClaimAsString("role"));

                assertEquals(
                                List.of("READ", "WRITE"),
                                jwt.getClaimAsStringList("permissions"));

                assertNotNull(jwt.getIssuedAt());
                assertNotNull(jwt.getExpiresAt());
                assertTrue(jwt.getExpiresAt().isAfter(Instant.now()));
        }

        @Test
        void shouldRejectInactiveUser() {

                User user = new User(
                                "U002",
                                "bob",
                                "OPERATOR",
                                false,
                                Set.of(Permission.WRITE));

                assertThrows(
                                IllegalArgumentException.class,
                                () -> jwtService.generateToken(user));
        }

        @Test
        void shouldRejectNullUser() {

                assertThrows(
                                IllegalArgumentException.class,
                                () -> jwtService.generateToken(null));
        }

        @Test
        void shouldRejectTamperedToken() {
                User user = new User(
                                "U001", "alice", "OPERATOR",
                                true, Set.of(Permission.READ));

                String token = jwtService.generateToken(user);

                String[] parts = token.split("\\.");
                assertEquals(3, parts.length);

                // Change a character in the signature.
                char first = parts[2].charAt(0);
                parts[2] = (first == 'A' ? 'B' : 'A')
                                + parts[2].substring(1);

                String tamperedToken = String.join(".", parts);

                assertThrows(
                                JwtException.class,
                                () -> decoder.decode(tamperedToken));
        }


        @Test
        void shouldRejectTokenSignedWithDifferentKey() {
                User user = new User(
                                "U001", "alice", "OPERATOR",
                                true, Set.of(Permission.READ));

                String token = jwtService.generateToken(user);

                byte[] otherKeyBytes = "different-test-secret-key-at-least-32-bytes"
                                .getBytes(StandardCharsets.UTF_8);

                SecretKey otherKey = new SecretKeySpec(otherKeyBytes, "HmacSHA256");

                JwtDecoder otherDecoder = NimbusJwtDecoder
                                .withSecretKey(otherKey)
                                .macAlgorithm(MacAlgorithm.HS256)
                                .build();

                assertThrows(
                                JwtException.class,
                                () -> otherDecoder.decode(token));
        }


        @Test
        void shouldRejectExpiredToken() {
                Instant now = Instant.now();

                JwtClaimsSet expiredClaims = JwtClaimsSet.builder()
                                .issuer("https://auth.mmiv.example")
                                .subject("U001")
                                .issuedAt(now.minus(2, ChronoUnit.HOURS))
                                .expiresAt(now.minus(1, ChronoUnit.HOURS))
                                .build();

                JwsHeader header = JwsHeader
                                .with(MacAlgorithm.HS256)
                                .build();

                String expiredToken = encoder.encode(
                                JwtEncoderParameters.from(header, expiredClaims)).getTokenValue();

                assertThrows(
                                JwtException.class,
                                () -> decoder.decode(expiredToken));
        }
}