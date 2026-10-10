
package mmiv.user_access_service.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;

@RestController
public class JwksController {

    private final RSAPublicKey publicKey;
    private final String keyId;

    public JwksController(
            RSAPublicKey publicKey,
            @Value("${jwt.key-id}") String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("JWT key ID must not be blank");
        }
        this.publicKey = publicKey;
        this.keyId = keyId;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();

        return new JWKSet(rsaKey).toJSONObject();
    }
}
