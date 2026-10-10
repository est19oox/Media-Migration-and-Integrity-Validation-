package mmiv.user_access_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;
import java.util.Set;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.InMemoryUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "eureka.client.enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@ContextConfiguration(classes = TestJwtConfig.class)
class UserAccessServiceApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InMemoryUserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void setUpUser() {
		userRepository.save(new User(
				"U001",
				List.of(new DestinationAccess("bucket-a", Permission.WRITE)),
				"alice",
				"OPERATOR",
				true,
				Set.of(Permission.READ),
				passwordEncoder.encode("password")));
	}

	@Test
	void contextLoads() {
	}

	@Test
	void successfulLoginAndPublishedJwksVerifyTheJwt() throws Exception {
		String loginResponse = mockMvc.perform(post("/api/auth/login")
						.contentType("application/json")
						.content("""
								{"username":"alice","password":"password"}
								"""))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String token = loginResponse.replaceFirst(
				".*\"accessToken\":\"([^\"]+)\".*", "$1");

		String jwksResponse = mockMvc.perform(get("/.well-known/jwks.json"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		RSAKey publicKey = (RSAKey) JWKSet.parse(jwksResponse)
				.getKeys()
				.get(0);
		assertFalse(jwksResponse.contains("\"d\""));
		assertFalse(jwksResponse.contains("\"p\""));
		assertFalse(jwksResponse.contains("\"q\""));
		assertFalse(jwksResponse.contains("passwordHash"));
		assertEquals("mmiv-user-access-key-1", publicKey.getKeyID());
		assertNotNull(publicKey.toRSAPublicKey());

		var jwt = jwtDecoder.decode(token);
		assertEquals("RS256", jwt.getHeaders().get("alg"));
		assertEquals("mmiv-user-access-key-1", jwt.getHeaders().get("kid"));
		assertEquals("https://auth.mmiv.example", jwt.getIssuer().toString());
		assertEquals("U001", jwt.getSubject());
		assertEquals(1800,
				jwt.getExpiresAt().getEpochSecond()
						- jwt.getIssuedAt().getEpochSecond());
		assertTrue(jwt.getTokenValue().equals(token));
	}

	@Test
	void invalidPasswordReturnsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.contentType("application/json")
						.content("""
								{"username":"alice","password":"wrong"}
								"""))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void missingCredentialsReturnBadRequest() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.contentType("application/json")
						.content("{}"))
				.andExpect(status().isBadRequest());
	}
}
