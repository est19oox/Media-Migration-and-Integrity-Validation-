package mmiv.transfer.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Feign encoding, discovery/load-balancing, endpoint mapping and response decoding. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "eureka.client.enabled=false")
class IntegrityFeignContractTest {
    static final HttpServer server;
    static final AtomicReference<String> body = new AtomicReference<>();
    static final String checksum = "a".repeat(64);
    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/integrity/validate", exchange -> {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String json = "{\"transferId\":\"transfer-1\",\"bucket\":\"bucket\",\"objectKey\":\"folder/key\","
                        + "\"algorithm\":\"SHA-256\",\"sourceChecksum\":\"" + checksum + "\","
                        + "\"destinationChecksum\":\"" + checksum + "\",\"valid\":true,\"validationResult\":\"PASSED\"}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void discovery(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.discovery.client.simple.instances.integrity-service[0].uri",
                () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @Autowired IntegrityValidationGateway gateway;
    @AfterAll static void stop() { server.stop(0); }
    @org.junit.jupiter.api.BeforeEach void authenticate() {
        var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture-token").header("alg","RS256").subject("fixture-user").build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
    }
    @org.junit.jupiter.api.AfterEach void clearIdentity() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void callsIntegrityUsingDiscoveredServiceNameAndJsonContract() {
        var result = gateway.validate("transfer-1", "bucket", "folder/key", checksum);
        assertTrue(result.success());
        assertEquals("PASSED", result.validationResult());
        assertTrue(body.get().contains("\"transferId\":\"transfer-1\""));
        assertTrue(body.get().contains("\"objectKey\":\"folder/key\""));
        assertTrue(body.get().contains(checksum));
        assertFalse(body.get().contains("sourcePath"));
    }
}
