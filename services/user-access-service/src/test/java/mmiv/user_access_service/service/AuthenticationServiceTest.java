package mmiv.user_access_service.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthenticationServiceTest {

  private UserRepository repository;
  private PasswordEncoder passwordEncoder;
  private AuthenticationService service;

  @BeforeEach
  void setUp() {
    repository = Mockito.mock(UserRepository.class);
    passwordEncoder = Mockito.mock(PasswordEncoder.class);
    service = new AuthenticationService(repository, passwordEncoder);
  }

  @Test
  void authenticateReturnsUserForValidCredentials() {
    User user = user(true, "$2a$12$stored");
    when(repository.findByUsername("alice")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("correct-password", user.getPasswordHash())).thenReturn(true);

    assertSame(user, service.authenticate("alice", "correct-password"));
  }

  @Test
  void authenticatePreservesDestinationAssignments() {
    User user = user(true, "$2a$12$stored");
    when(repository.findByUsername("alice")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

    User authenticated = service.authenticate("alice", "password");

    assertEquals(user.getDestinationAccesses(), authenticated.getDestinationAccesses());
    assertEquals(Permission.WRITE, authenticated.getDestinationAccesses().get(0).getPermission());
  }

  @Test
  void rejectsUnknownUserAndDoesNotCheckPassword() {
    when(repository.findByUsername("unknown")).thenReturn(Optional.empty());

    assertThrows(
        InvalidCredentialsException.class, () -> service.authenticate("unknown", "password"));
    verify(passwordEncoder, never()).matches(anyString(), anyString());
  }

  @Test
  void rejectsIncorrectPassword() {
    User user = user(true, "$2a$12$stored");
    when(repository.findByUsername("alice")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong-password", user.getPasswordHash())).thenReturn(false);

    assertThrows(
        InvalidCredentialsException.class, () -> service.authenticate("alice", "wrong-password"));
  }

  @Test
  void rejectsInactiveUser() {
    User user = user(false, "$2a$12$stored");
    when(repository.findByUsername("alice")).thenReturn(Optional.of(user));

    assertThrows(
        InvalidCredentialsException.class, () -> service.authenticate("alice", "password"));
    verify(passwordEncoder, never()).matches(anyString(), anyString());
  }

  @Test
  void rejectsMissingPasswordHash() {
    User user = user(true, null);
    when(repository.findByUsername("alice")).thenReturn(Optional.of(user));

    assertThrows(
        InvalidCredentialsException.class, () -> service.authenticate("alice", "password"));
    verify(passwordEncoder, never()).matches(anyString(), anyString());
  }

  @Test
  void rejectsPasswordSuffixBeyondBcryptByteLimit() {
    BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
    AuthenticationService actual = new AuthenticationService(repository, bcrypt);
    for (String prefix : List.of("a".repeat(72), "界".repeat(24))) {
      User user = user(true, bcrypt.encode(prefix));
      when(repository.findByUsername("alice")).thenReturn(Optional.of(user));
      assertSame(user, actual.authenticate("alice", prefix));
      assertThrows(
          InvalidCredentialsException.class, () -> actual.authenticate("alice", prefix + "x"));
    }
  }

  @Test
  void rejectsBlankCredentialsWithoutRepositoryLookup() {
    assertThrows(InvalidCredentialsException.class, () -> service.authenticate(" ", "password"));
    assertThrows(InvalidCredentialsException.class, () -> service.authenticate("alice", ""));
    verify(repository, never()).findByUsername(anyString());
  }

  private static User user(boolean active, String passwordHash) {
    return new User(
        "U001",
        List.of(new DestinationAccess("bucket-a", Permission.WRITE)),
        "alice",
        "OPERATOR",
        active,
        Set.of(Permission.READ),
        passwordHash);
  }
}
