package mmiv.user_access_service.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import mmiv.user_access_service.dto.SignupRequest;
import mmiv.user_access_service.repository.InMemoryUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class RegistrationServiceTest {
  final InMemoryUserRepository users = new InMemoryUserRepository();
  final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
  final RegistrationService registration = new RegistrationService(users, passwords);

  @Test
  void newAccountCanLogInAndHasNoElevatedPermissions() {
    var result = registration.register(new SignupRequest("alice", "password123"));
    var saved = users.findByUsername("alice").orElseThrow();
    assertEquals(result.userId(), saved.getUserId());
    assertNotEquals("password123", saved.getPasswordHash());
    assertTrue(passwords.matches("password123", saved.getPasswordHash()));
    assertEquals("USER", saved.getRole());
    assertTrue(saved.getPermissions().isEmpty());
    assertTrue(saved.getDestinationAccesses().isEmpty());
    assertEquals(
        result.userId(),
        new AuthenticationService(users, passwords).authenticate("alice", "password123").getUserId());
  }

  @Test
  void duplicateNeverOverwritesTheOriginalPassword() {
    registration.register(new SignupRequest("alice", "original123"));
    assertThrows(
        DuplicateUsernameException.class,
        () -> registration.register(new SignupRequest("alice", "replacement123")));
    assertTrue(passwords.matches("original123", users.findByUsername("alice").orElseThrow().getPasswordHash()));
  }

  @Test
  void concurrentSignupsCreateExactlyOneAccount() throws Exception {
    AtomicInteger created = new AtomicInteger();
    Callable<Void> signup = () -> {
      try {
        registration.register(new SignupRequest("same-user", "password123"));
        created.incrementAndGet();
      } catch (DuplicateUsernameException expected) {
        // The duplicate request must not overwrite the winner.
      }
      return null;
    };
    try (var pool = Executors.newFixedThreadPool(2)) {
      for (var result : pool.invokeAll(List.of(signup, signup))) result.get();
    }
    assertEquals(1, created.get());
  }

  @Test
  void invalidNamesAndOversizedUtf8PasswordsAreRejected() {
    for (var request : List.of(
        new SignupRequest("ab", "password123"),
        new SignupRequest("admin name", "password123"),
        new SignupRequest("alice", "short"),
        new SignupRequest("alice", "密".repeat(25)))) {
      assertThrows(InvalidSignupException.class, () -> registration.register(request));
    }
    assertTrue(users.findByUsername("alice").isEmpty());
  }
}
