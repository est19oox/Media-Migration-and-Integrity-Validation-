package mmiv.transfer.config;

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
import mmiv.transfer.controller.BrowserSessionController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {BrowserSessionController.class, TransferSecurityIntegrationTest.ProtectedProbe.class})
@ContextConfiguration(classes = {
        TransferSecurityConfig.class,
        BrowserSessionController.class,
        TransferSecurityIntegrationTest.ProtectedProbe.class
})
class TransferSecurityIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean mmiv.events.EventPublisher auditPublisher;

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
            JWKS.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.start();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mmiv.auth.issuer", () -> ISSUER);
        registry.add("mmiv.auth.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void closeServer() { JWKS.stop(0); }
    @org.springframework.test.context.bean.override.mockito.MockitoBean mmiv.transfer.integration.MetadataClient metadata;
    @Autowired MockMvc mvc;

    @Test void revokedDestinationReturns403AndPermissionOutageReturns503() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(metadata).requireAccess("READ");
        mvc.perform(get("/api/v1/files/probe").header("Authorization","Bearer "+token(ISSUER,Instant.now().plusSeconds(300))))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .when(metadata).requireAccess("READ");
        mvc.perform(get("/api/v1/files/probe").header("Authorization","Bearer "+token(ISSUER,Instant.now().plusSeconds(300))))
                .andExpect(status().isServiceUnavailable());
    }
    static String token(String issuer, Instant expiry) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                new JWTClaimsSet.Builder().issuer(issuer).subject("U001")
                        .issueTime(Date.from(Instant.now().minusSeconds(300)))
                        .expirationTime(Date.from(expiry)).build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
        return jwt.serialize();
    }
    static String valid() throws Exception { return token(ISSUER, Instant.now().plusSeconds(600)); }

    @Test void anonymousPagesRedirectAndFileApiRejects() throws Exception {
        for (String page : new String[] {"/", "/upload.html", "/files.html"}) {
            mvc.perform(get(page).header("Host", "public.example:8080")
                            .header("X-Forwarded-Host", "public.example")
                            .header("X-Forwarded-Proto", "https"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/login.html"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get("/files.html")).andExpect(status().isFound());
        mvc.perform(get("/api/v1/files/test/content")).andExpect(status().isUnauthorized());
        mvc.perform(head("/api/v1/files/test/download")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/transfers").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void realSignedBearerEstablishesHttpOnlyCookieAndLoadsPage() throws Exception {
        String jwt = valid();
        var result = mvc.perform(post("/api/v1/session").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent()).andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(cookie);
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        mvc.perform(get("/upload.html").cookie(new Cookie("MMIV_SESSION", jwt)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/files/test/content").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
    }

    @Test void expiredWrongIssuerAndForgedTokensAreRejected() throws Exception {
        String signed = valid();
        int signatureStart = signed.lastIndexOf('.') + 1;
        String forged = signed.substring(0, signatureStart)
                + (signed.charAt(signatureStart) == 'A' ? 'B' : 'A') + signed.substring(signatureStart + 1);
        for (String jwt : new String[] {token(ISSUER, Instant.now().minusSeconds(1)),
                token("https://wrong.example", Instant.now().plusSeconds(600)),
                forged}) {
            mvc.perform(get("/api/v1/files/test/content").header("Authorization", "Bearer " + jwt))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/upload.html").cookie(new Cookie("MMIV_SESSION", "forged")))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login.html"));
    }

    @Test void cookieUploadsNeedCsrfAndLogoutClearsCookie() throws Exception {
        Cookie auth = new Cookie("MMIV_SESSION", valid());
        mvc.perform(post("/api/v1/transfers").cookie(auth)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF_INVALID"));
        mvc.perform(delete("/api/v1/session").cookie(auth)).andExpect(status().isForbidden());
        var result = mvc.perform(get("/api/v1/session").cookie(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").isNumber()).andReturn();
        // The endpoint returns the masked CSRF value while the cookie stores the expected token.
        String body = result.getResponse().getContentAsString();
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        Cookie csrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrf);
        String header = json.get("csrfHeader").asString();
        String value = json.get("csrfToken").asString();
        mvc.perform(post("/api/v1/transfers").cookie(auth, csrf).header(header, "invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/transfers").cookie(auth, csrf).header(header, value))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/session").cookie(auth, csrf).header(header, value))
                .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test void explicitBearerUploadDoesNotRequireBrowserCsrf() throws Exception {
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + valid()))
                .andExpect(status().isOk());
        mvc.perform(get("/not-exposed").header("Authorization", "Bearer " + valid()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/transfers").cookie(new Cookie("MMIV_SESSION", valid()))
                .header("Authorization", "Basic invalid"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class ProtectedProbe {
        @GetMapping("/upload.html") String page() { return "protected"; }
        @GetMapping("/api/v1/files/test/content") String file() { return "file"; }
        @PostMapping("/api/v1/transfers") String upload() { return "uploaded"; }
    }
}
