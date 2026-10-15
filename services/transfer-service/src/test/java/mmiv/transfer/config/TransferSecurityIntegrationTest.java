package mmiv.transfer.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

@WebMvcTest(controllers = TransferSecurityIntegrationTest.ProtectedProbe.class)
@ContextConfiguration(
    classes = {TransferSecurityConfig.class, TransferSecurityIntegrationTest.ProtectedProbe.class})
class TransferSecurityIntegrationTest {
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  mmiv.events.EventPublisher auditPublisher;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  mmiv.transfer.integration.MetadataClient metadata;

  static final String ISSUER = "https://auth.mmiv.example";
  static final KeyPair KEYS;
  static final HttpServer JWKS;

  static {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KEYS = generator.generateKeyPair();
      RSAKey key = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).keyID("test-key").build();
      byte[] body = new JWKSet(key).toString().getBytes(StandardCharsets.UTF_8);
      JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      JWKS.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      JWKS.start();
    } catch (Exception error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("mmiv.auth.issuer", () -> ISSUER);
    registry.add(
        "mmiv.auth.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
  }

  @AfterAll
  static void closeServer() {
    JWKS.stop(0);
  }

  @Autowired MockMvc mvc;

  @Test
  void bearerStillAuthenticatesTransferApi() throws Exception {
    mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + valid()))
        .andExpect(status().isOk());
  }

  @Test
  void mmivSessionCookieAloneNoLongerAuthenticatesTransferDirectly() throws Exception {
    mvc.perform(
            get("/api/v1/files/"
                    + mmiv.transfer.files.FileIds.encode("SW001/test.txt")
                    + "/content")
                .cookie(new Cookie("MMIV_SESSION", valid())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void sessionEndpointNoLongerExistsInTransfer() throws Exception {
    mvc.perform(post("/api/v1/session").header("Authorization", "Bearer " + valid()))
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidBearerTokensAreRejected() throws Exception {
    String signed = valid();
    int signatureStart = signed.lastIndexOf('.') + 1;
    String forged =
        signed.substring(0, signatureStart)
            + (signed.charAt(signatureStart) == 'A' ? 'B' : 'A')
            + signed.substring(signatureStart + 1);
    for (String jwt :
        new String[] {
          token(ISSUER, Instant.now().minusSeconds(1)),
          token("https://wrong.example", Instant.now().plusSeconds(600)),
          forged
        }) {
      mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + jwt))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void destinationPermissionFailuresStillPropagate() throws Exception {
    org.mockito.Mockito.doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN))
        .when(metadata)
        .requireFolder("SW001", "READ");
    mvc.perform(
            get("/api/v1/files/"
                    + mmiv.transfer.files.FileIds.encode("SW001/probe.txt")
                    + "/content")
                .header("Authorization", "Bearer " + valid()))
        .andExpect(status().isForbidden());
  }

  static String token(String issuer, Instant expiry) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("U001")
                .issueTime(Date.from(Instant.now().minusSeconds(300)))
                .expirationTime(Date.from(expiry))
                .build());
    jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
    return jwt.serialize();
  }

  static String valid() throws Exception {
    return token(ISSUER, Instant.now().plusSeconds(600));
  }

  @RestController
  static class ProtectedProbe {
    @GetMapping("/api/v1/files/{id}/content")
    String file() {
      return "file";
    }

    @PostMapping("/api/v1/transfers")
    String upload() {
      return "uploaded";
    }
  }
}
