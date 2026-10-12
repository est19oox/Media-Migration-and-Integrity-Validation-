package mmiv.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;

import mmiv.csv.MetadataCsvValidationError;
import mmiv.csv.MetadataCsvValidationException;
import mmiv.model.DiscoveryRunResult;
import mmiv.model.MigrationFileInventory;
import mmiv.repository.FileMetadataRepository;
import mmiv.repository.ValidationLogRepository;
import mmiv.runner.DiscoveryRunner;
import mmiv.service.DiscoveryService;
import mmiv.service.ManifestProcessingService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the bearer-token rules of the Discovery Service against the real
 * security filter chain and the real {@code jwt.jwk-set-uri} code path.
 *
 * <p>A throwaway JWKS endpoint is served over loopback on a random port and the
 * production decoder is pointed at it, so token verification performs an actual
 * key-set lookup exactly as it does against the User Access Service. The key
 * pair is generated per test run and never touches disk.
 *
 * <p>Authorization is intentionally out of scope: these tests only establish
 * that a caller is authenticated, not what a caller may do.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscoverySecurityIntegrationTest {

    private static final String ISSUER = "https://auth.mmiv.example";
    private static final String WRONG_ISSUER = "https://attacker.example";

    private static final JwtTestSigner SIGNER = JwtTestSigner.create();

    private static HttpServer jwksServer;
    private static String jwksUri;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiscoveryService discoveryService;

    @MockitoBean
    private ManifestProcessingService manifestProcessingService;

    @MockitoBean
    private FileMetadataRepository fileMetadataRepository;

    @MockitoBean
    private ValidationLogRepository validationLogRepository;

    @MockitoBean
    private DiscoveryRunner discoveryRunner;

    /**
     * Serves the generated public key set at the same path the User Access
     * Service publishes, so the decoder follows the production code path.
     */
    @BeforeAll
    static void startJwksEndpoint() throws IOException {

        String body = new ObjectMapper().writeValueAsString(
                SIGNER.publicJwkSet().toJSONObject()
        );

        jwksServer = HttpServer.create(new InetSocketAddress(0), 0);

        jwksServer.createContext(
                "/.well-known/jwks.json",
                exchange -> {
                    byte[] response =
                            body.getBytes(StandardCharsets.UTF_8);

                    exchange.getResponseHeaders().set(
                            "Content-Type",
                            "application/json"
                    );
                    exchange.sendResponseHeaders(200, response.length);

                    try (OutputStream output =
                                 exchange.getResponseBody()) {
                        output.write(response);
                    }
                }
        );

        jwksServer.start();

        jwksUri = "http://localhost:"
                + jwksServer.getAddress().getPort()
                + "/.well-known/jwks.json";
    }

    @AfterAll
    static void stopJwksEndpoint() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("jwt.issuer", () -> ISSUER);
        registry.add("jwt.jwk-set-uri", () -> jwksUri);
    }

    // ------------------------------------------------------------------
    // Missing and malformed credentials
    // ------------------------------------------------------------------

    @Test
    void missingTokenIsRejected() throws Exception {

        mockMvc.perform(post("/api/discovery/scan"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedTokenIsRejected() throws Exception {

        mockMvc.perform(post("/api/discovery/scan")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer not-a-jwt"
                        ))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsupportedAuthorizationSchemeIsRejected() throws Exception {

        mockMvc.perform(post("/api/discovery/scan")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Basic YWxpY2U6c2VjcmV0"
                        ))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Invalid token properties
    // ------------------------------------------------------------------

    @Test
    void expiredTokenIsRejected() throws Exception {

        Instant issuedAt = Instant.now().minus(2, ChronoUnit.HOURS);

        String token = SIGNER.token(
                ISSUER,
                issuedAt,
                issuedAt.plus(1, ChronoUnit.HOURS)
        );

        mockMvc.perform(scanWith(token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {

        String token = SIGNER.tokenSignedByOtherKey(
                ISSUER,
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        mockMvc.perform(scanWith(token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithUnknownKeyIdIsRejected() throws Exception {

        String token = SIGNER.tokenWithKeyId(
                ISSUER,
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.MINUTES),
                "unknown-key-id"
        );

        mockMvc.perform(scanWith(token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {

        String token = SIGNER.token(
                WRONG_ISSUER,
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );

        mockMvc.perform(scanWith(token))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Valid credentials
    // ------------------------------------------------------------------

    @Test
    void validTokenIsAccepted() throws Exception {

        when(discoveryService.discoverAndSaveWithResult(any()))
                .thenReturn(new DiscoveryRunResult(
                        new MigrationFileInventory(List.of()),
                        0
                ));

        mockMvc.perform(scanWith(validToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discovered").value(0))
                .andExpect(jsonPath("$.inserted").value(0));
    }

    /**
     * The manifest endpoint is protected by the same rule as the scan endpoint.
     * A valid token reaches the controller, which reports the absent manifest as
     * 404; the important part here is that the request was not rejected with 401
     * and that the service was actually invoked.
     */
    @Test
    void validTokenIsAcceptedOnTheManifestEndpoint() throws Exception {

        when(manifestProcessingService.processManifest(any(), any()))
                .thenThrow(new MetadataCsvValidationException(List.of(
                        new MetadataCsvValidationError(
                                0,
                                "File",
                                "CSV file does not exist"
                        )
                )));

        mockMvc.perform(post("/api/discovery/manifest")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + validToken()
                        ))
                .andExpect(status().isNotFound());

        verify(manifestProcessingService)
                .processManifest(any(), any());
    }

    @Test
    void manifestEndpointRejectsAMissingToken() throws Exception {

        mockMvc.perform(post("/api/discovery/manifest"))
                .andExpect(status().isUnauthorized());

        verify(manifestProcessingService, never())
                .processManifest(any(), any());
    }

    // ------------------------------------------------------------------
    // Public endpoints
    // ------------------------------------------------------------------

    @Test
    void healthEndpointIsPublic() throws Exception {

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    /**
     * A malformed credential is rejected before the permit-all rule is applied,
     * even on a public endpoint. This is deliberate: an unusable token never
     * silently degrades into an anonymous request. Health checks are unaffected
     * because they send no {@code Authorization} header.
     */
    @Test
    void malformedTokenIsRejectedEvenOnThePublicHealthEndpoint()
            throws Exception {

        mockMvc.perform(get("/actuator/health")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer not-a-jwt"
                        ))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownDiscoveryPathRequiresAuthentication()
            throws Exception {

        mockMvc.perform(get("/api/discovery/unknown"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static org.springframework.test.web.servlet.request
            .MockHttpServletRequestBuilder scanWith(String token) {

        return post("/api/discovery/scan")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + token
                );
    }

    private static String validToken() {

        return SIGNER.token(
                ISSUER,
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
    }
}
