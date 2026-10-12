package mmiv.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Test-only RSA key material and token builder for the Discovery security tests.
 *
 * <p>Generates a throwaway 2048-bit RSA key pair in memory, signs tokens the
 * same way the User Access Service does (RS256, {@code kid} in the header), and
 * publishes the public half as a JWK set so a test can serve it from a JWKS
 * endpoint. No key material is written to disk and no shared fixture is used, so
 * tests cannot influence each other.
 *
 * <p>Every claim is explicit at the call site so each test states exactly which
 * property of the token makes it valid or invalid.
 */
final class JwtTestSigner {

    static final String KEY_ID = "mmiv-test-key-1";

    private final RSAKey rsaKey;
    private final RSASSASigner signer;

    private JwtTestSigner(RSAKey rsaKey, RSASSASigner signer) {
        this.rsaKey = rsaKey;
        this.signer = signer;
    }

    /**
     * Creates a signer with a fresh in-memory RSA key pair.
     */
    static JwtTestSigner create() {
        try {

            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            RSAKey rsaKey = new RSAKey.Builder(
                    (RSAPublicKey) keyPair.getPublic()
            )
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(KEY_ID)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();

            return new JwtTestSigner(
                    rsaKey,
                    new RSASSASigner(keyPair.getPrivate())
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not create the test RSA key pair",
                    exception
            );
        }
    }

    /**
     * The public key set to publish from a JWKS endpoint.
     */
    JWKSet publicJwkSet() {
        return new JWKSet(rsaKey.toPublicJWK());
    }

    /**
     * Builds the default valid token: correct issuer, correct key, issued now
     * and valid for 30 minutes.
     */
    String token(String issuer, Instant issuedAt, Instant expiresAt) {
        return token(issuer, issuedAt, expiresAt, KEY_ID, signer);
    }

    /**
     * Builds a token with an explicit key ID, so a test can present a
     * {@code kid} that the JWKS endpoint does not publish.
     */
    String tokenWithKeyId(
            String issuer,
            Instant issuedAt,
            Instant expiresAt,
            String keyId
    ) {
        return token(issuer, issuedAt, expiresAt, keyId, signer);
    }

    /**
     * Builds a token signed by a different key, for invalid-signature cases.
     */
    String tokenSignedByOtherKey(
            String issuer,
            Instant issuedAt,
            Instant expiresAt
    ) {
        JwtTestSigner other = JwtTestSigner.create();

        return token(
                issuer,
                issuedAt,
                expiresAt,
                KEY_ID,
                other.signer
        );
    }

    private static String token(
            String issuer,
            Instant issuedAt,
            Instant expiresAt,
            String keyId,
            RSASSASigner signingKey
    ) {
        try {

            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject("U001")
                    .claim("username", "alice")
                    .claim("role", "OPERATOR")
                    .claim("permissions", java.util.List.of("READ"))
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString())
                    .build();

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID(keyId)
                            .build(),
                    claims
            );

            jwt.sign(signingKey);

            return jwt.serialize();

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not sign the test token",
                    exception
            );
        }
    }
}
