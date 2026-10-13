package mmiv.user_access_service.controller;
import java.time.Instant;
import java.util.*;
import mmiv.events.EventPublisher;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.InMemoryUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Import(TestJwtConfig.class)
class AccountManagementIntegrationTest {
 @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;@org.springframework.beans.factory.annotation.Value("${jwt.key-id}")String keyId;@Autowired MockMvc mvc;@Autowired InMemoryUserRepository users;@Autowired PasswordEncoder passwords;@MockitoBean EventPublisher events;
 final String admin=UUID.randomUUID().toString(),other=UUID.randomUUID().toString(),user=UUID.randomUUID().toString();
 @BeforeEach void setup(){users.deleteAccount(admin,user);users.deleteAccount(admin,admin);users.create(new User(admin,admin,"ADMIN",true,Set.of(),passwords.encode("old-password")));users.save(new User(other,other,"ADMIN",true,Set.of(),passwords.encode("old-password")));users.create(new User(user,user,"USER",true,Set.of(),passwords.encode("old-password")));}
 @AfterEach void cleanup(){if(users.findById(admin).isEmpty())users.create(new User(admin,admin,"ADMIN",true,Set.of(),"fixture"));var target=users.findById(user);if(target.isPresent()&&"ADMIN".equals(target.get().getRole()))users.deleteAccount(user,user);else users.deleteAccount(admin,user);users.deleteAccount(admin,admin);users.deleteAccount(other,other);}
 org.springframework.test.web.servlet.request.RequestPostProcessor caller(String id,long version){var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("https://auth.mmiv.example").subject(id).claim("ver",version).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();var header=org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).keyId(keyId).build();String token=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header,claims)).getTokenValue();return request->{request.addHeader("Authorization","Bearer "+token);return request;};}
 String link()throws Exception {String body=mvc.perform(post("/api/admin/users/"+user+"/password-reset").with(caller(admin,0))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();return tools.jackson.databind.json.JsonMapper.builder().build().readTree(body).path("token").stringValue();}
 @Test void resetIsOneUseAndInvalidatesOldSessionsButNewSessionWorks()throws Exception {String token=link();mvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\""+token+"\",\"password\":\"new-password\"}")).andExpect(status().isOk());assertTrue(passwords.matches("new-password",users.findById(user).orElseThrow().getPasswordHash()));mvc.perform(get("/api/access/me").with(caller(user,0))).andExpect(status().isUnauthorized());mvc.perform(get("/api/access/me").with(caller(user,1))).andExpect(status().isOk());mvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\""+token+"\",\"password\":\"another-password\"}")).andExpect(status().isBadRequest());}
 @Test void ordinaryUserCannotCreateResetOrDeleteAccountAndOtherAdminIsProtected()throws Exception {mvc.perform(post("/api/admin/users/"+user+"/password-reset").with(caller(user,0))).andExpect(status().isForbidden());mvc.perform(delete("/api/admin/users/"+user).with(caller(user,0))).andExpect(status().isForbidden());mvc.perform(delete("/api/admin/users/"+other).with(caller(admin,0))).andExpect(status().isForbidden());mvc.perform(post("/api/admin/users/"+other+"/password-reset").with(caller(admin,0))).andExpect(status().isForbidden());assertTrue(users.findById(other).isPresent());}
 @Test void deleteUserThenAdministratorSelfDeactivatesAccounts()throws Exception {mvc.perform(delete("/api/admin/users/"+user).with(caller(admin,0))).andExpect(status().isNoContent());assertFalse(users.findById(user).orElseThrow().isActive());mvc.perform(get("/api/access/me").with(caller(user,0))).andExpect(status().isUnauthorized());mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+user+"\",\"password\":\"old-password\"}")).andExpect(status().isUnauthorized());mvc.perform(delete("/api/admin/users/"+admin).with(caller(admin,0))).andExpect(status().isNoContent());assertFalse(users.findById(admin).orElseThrow().isActive());mvc.perform(get("/api/admin/me").with(caller(admin,0))).andExpect(status().isUnauthorized());assertTrue(users.findById(other).isPresent());}
 @Test void promotingUserInvalidatesResetLinksIssuedByOtherAdministrators()throws Exception {String token=link();users.createAdminInvitation(user,"promotion",Instant.now().plusSeconds(60),admin);assertTrue(users.acceptAdminInvitation(user,"promotion",Instant.now()));mvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\""+token+"\",\"password\":\"new-password\"}")).andExpect(status().isBadRequest());assertTrue(passwords.matches("old-password",users.findById(user).orElseThrow().getPasswordHash()));}
 @Test void expiredResetDoesNotConsumeAndMissingOrWeakPasswordsDoNotReset()throws Exception {users.createPasswordReset(admin,user,"expired",Instant.now().minusSeconds(1));assertTrue(users.consumePasswordReset("expired",()->"hash",Instant.now()).isEmpty());String token=link();mvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\""+token+"\",\"password\":\"short\"}")).andExpect(status().isBadRequest());assertTrue(passwords.matches("old-password",users.findById(user).orElseThrow().getPasswordHash()));}
}
