package mmiv.user_access_service.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;
    private JwtDecoder decoder;
    private JwtEncoder encoder;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair keyPair = generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(
                new JWKSet(new RSAKey.Builder(publicKey)
                        .privateKey(privateKey)
                        .keyID("mmiv-test-key")
                        .build())));

        decoder = NimbusJwtDecoder
                .withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
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
        Jwt jwt = decoder.decode(token);

        assertEquals("RS256", jwt.getHeaders().get("alg"));
        assertEquals("mmiv-test-key", jwt.getHeaders().get("kid"));
        assertEquals("U001", jwt.getSubject());
        assertEquals("https://auth.mmiv.example", jwt.getIssuer().toString());
        assertEquals("alice", jwt.getClaimAsString("username"));
        assertEquals("OPERATOR", jwt.getClaimAsString("role"));
        assertEquals(List.of("READ", "WRITE"),
                jwt.getClaimAsStringList("permissions"));
        assertNotNull(jwt.getIssuedAt());
        assertNotNull(jwt.getExpiresAt());
        assertEquals(30, ChronoUnit.MINUTES.between(
                jwt.getIssuedAt(), jwt.getExpiresAt()));
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

        assertThrows(IllegalArgumentException.class,
                () -> jwtService.generateToken(user));
    }

    @Test
    void shouldRejectNullUser() {
        assertThrows(IllegalArgumentException.class,
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

        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);

        assertThrows(JwtException.class,
                () -> decoder.decode(String.join(".", parts)));
    }

    @Test
    void shouldRejectTokenSignedWithDifferentKey() throws Exception {
        User user = new User(
                "U001", "alice", "OPERATOR",
                true, Set.of(Permission.READ));

        String token = jwtService.generateToken(user);
        RSAPublicKey otherPublicKey =
                (RSAPublicKey) generateKeyPair().getPublic();
        JwtDecoder otherDecoder = NimbusJwtDecoder
                .withPublicKey(otherPublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        assertThrows(JwtException.class,
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
                .with(SignatureAlgorithm.RS256)
                .build();
        String expiredToken = encoder.encode(
                JwtEncoderParameters.from(header, expiredClaims))
                .getTokenValue();

        assertThrows(JwtException.class,
                () -> decoder.decode(expiredToken));
    }

    @Test
    void shouldGenerateRS256SignedToken() {
        User user = new User(
                "U001",
                "alice",
                "OPERATOR",
                true,
                Set.of(Permission.READ, Permission.WRITE));

        Jwt jwt = decoder.decode(jwtService.generateToken(user));

        assertEquals("RS256", jwt.getHeaders().get("alg"));
        assertEquals("mmiv-test-key", jwt.getHeaders().get("kid"));
        assertEquals("https://auth.mmiv.example", jwt.getIssuer().toString());
        assertEquals("U001", jwt.getSubject());
        assertEquals("alice", jwt.getClaimAsString("username"));
        assertEquals("OPERATOR", jwt.getClaimAsString("role"));
        assertEquals(List.of("READ", "WRITE"),
                jwt.getClaimAsStringList("permissions"));
        assertNotNull(jwt.getIssuedAt());
        assertNotNull(jwt.getExpiresAt());
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}