package mmiv.user_access_service.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import mmiv.user_access_service.config.TestJwtConfig;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import mmiv.user_access_service.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class VerificationAccountControllerTest {
  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired JwtService tokens;

  private User user(String name, String role) {
    User user =
        new User(UUID.randomUUID().toString(), List.of(), name, role, true, Set.of(), "hash");
    users.create(user);
    return user;
  }

  @Test
  void deletesOnlyTheAuthenticatedGeneratedUser() throws Exception {
    User caller = user("mmiv_ci_" + "a".repeat(32), "USER");
    User other = user("mmiv_ci_" + "b".repeat(32), "USER");
    mvc.perform(
            delete("/api/auth/verification-account")
                .header("Authorization", "Bearer " + tokens.generateToken(caller)))
        .andExpect(status().isNoContent());
    assertTrue(users.findById(caller.getUserId()).isEmpty());
    assertTrue(users.findById(other.getUserId()).isPresent());
  }

  @Test
  void rejectsOrdinaryAccountsAndPrivilegedAccounts() throws Exception {
    for (User user :
        List.of(
            user("normal-" + UUID.randomUUID(), "USER"),
            user("mmiv_ci_" + "c".repeat(32), "ADMIN"))) {
      mvc.perform(
              delete("/api/auth/verification-account")
                  .header("Authorization", "Bearer " + tokens.generateToken(user)))
          .andExpect(status().isForbidden());
      assertTrue(users.findById(user.getUserId()).isPresent());
    }
  }

  @Test
  void rejectsAnonymousAndForgedRequests() throws Exception {
    mvc.perform(delete("/api/auth/verification-account")).andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/auth/verification-account").header("Authorization", "Bearer forged"))
        .andExpect(status().isUnauthorized());
  }
}
