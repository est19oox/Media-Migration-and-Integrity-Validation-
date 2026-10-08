
package mmiv.user_access_service.controller;

import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.OracleUserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.security.interfaces.RSAPublicKey;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class AuthLoginIT {

    private String testUserId;
    private String testUsername;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OracleUserRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RSAPublicKey publicKey;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        testUserId = "IT_AUTH_" + suffix;
        testUsername = "it_alice_" + suffix;

        User user = new User(
                testUserId,
                testUsername,
                "OPERATOR",
                true,
                Set.of(Permission.READ, Permission.WRITE),
                passwordEncoder.encode("correct-password"));

        repository.save(user);
    }

    @AfterEach
    void cleanUp() {
        if (testUserId != null) {
            jdbcTemplate.update(
                    "DELETE FROM app_users WHERE user_id = ?",
                    testUserId);
        }
    }

    @Test
    void shouldLoginWithCorrectPassword() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                        {
                          "username": "%s",
                          "password": "correct-password"
                        }
                        """.formatted(testUsername)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(response.contains("accessToken"));
    }

    @Test
    void shouldRejectWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                        {
                          "username": "%s",
                          "password": "wrong-password"
                        }
                        """.formatted(testUsername)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnValidSignedJwtWithCorrectClaims() throws Exception {

        String responseBody = mockMvc.perform(
                post("/api/auth/login")
                        .contentType("application/json")
                        .content("""
                                {
                                "username": "%s",
                                "password": "correct-password"
                                }
                                """.formatted(testUsername)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        ObjectMapper objectMapper = new ObjectMapper();

        JsonNode responseJson = objectMapper.readTree(responseBody);

        String token = responseJson.get("accessToken").asText();

        assertNotNull(token);
        assertFalse(token.isBlank());

        JwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        Jwt jwt = decoder.decode(token);
        assertEquals("RS256", jwt.getHeaders().get("alg"));

        assertEquals(testUserId, jwt.getSubject());

        assertEquals(
                "https://auth.mmiv.example",
                jwt.getIssuer().toString());

        assertEquals(
                testUsername,
                jwt.getClaimAsString("username"));

        assertEquals(
                "OPERATOR",
                jwt.getClaimAsString("role"));

        assertEquals(
                List.of("READ", "WRITE"),
                jwt.getClaimAsStringList("permissions"));

        assertNotNull(jwt.getIssuedAt());
        assertNotNull(jwt.getExpiresAt());

        assertTrue(jwt.getExpiresAt().isAfter(Instant.now()));

        assertEquals(
                1800,
                jwt.getExpiresAt().getEpochSecond()
                        - jwt.getIssuedAt().getEpochSecond());
    }

    @Test
    void shouldRejectInactiveUser() throws Exception {
        User inactiveUser = new User(
                testUserId,
                testUsername,
                "OPERATOR",
                false,
                Set.of(Permission.READ),
                passwordEncoder.encode("correct-password"));

        repository.save(inactiveUser);

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                        {
                          "username": "%s",
                          "password": "correct-password"
                        }
                        """.formatted(testUsername)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectUnknownUser() throws Exception {
        String unknownUsername = "unknown_" + UUID.randomUUID();

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                        {
                          "username": "%s",
                          "password": "some-password"
                        }
                        """.formatted(unknownUsername)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectMissingPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                        {
                          "username": "%s"
                        }
                        """.formatted(testUsername)))
                .andExpect(status().isBadRequest());
    }
}
