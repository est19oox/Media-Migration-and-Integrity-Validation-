
package mmiv.user_access_service.service;

import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationServiceTest {

    private UserRepository repository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);

        service = new AuthenticationService(
                repository,
                passwordEncoder,
                jwtService);
    }

    private User activeUser() {
        return new User(
                "U001",
                "alice",
                "OPERATOR",
                true,
                Set.of(Permission.READ, Permission.WRITE),
                "stored-bcrypt-hash");
    }

    @Test
    void shouldReturnJwtForValidCredentials() {
        User user = activeUser();

        when(repository.findByUsername("alice"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "correct-password", user.getPasswordHash()))
                .thenReturn(true);

        when(jwtService.generateToken(user))
                .thenReturn("signed-jwt-token");

        String token = service.login("alice", "correct-password");

        assertEquals("signed-jwt-token", token);
        verify(jwtService).generateToken(user);
    }

    @Test
    void shouldRejectIncorrectPassword() {
        User user = activeUser();

        when(repository.findByUsername("alice"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "wrong-password", user.getPasswordHash()))
                .thenReturn(false);

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login("alice", "wrong-password"));

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void shouldRejectUnknownUsername() {
        when(repository.findByUsername("unknown"))
                .thenReturn(Optional.empty());

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login("unknown", "password"));

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void shouldRejectInactiveUser() {
        User user = new User(
                "U002",
                "bob",
                "OPERATOR",
                false,
                Set.of(Permission.WRITE),
                "stored-bcrypt-hash");

        when(repository.findByUsername("bob"))
                .thenReturn(Optional.of(user));

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login("bob", "password"));

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void shouldRejectUserWithoutPasswordHash() {
        User user = new User(
                "U003",
                "carol",
                "VIEWER",
                true,
                Set.of(Permission.READ));

        when(repository.findByUsername("carol"))
                .thenReturn(Optional.of(user));

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login("carol", "password"));

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void shouldRejectBlankCredentials() {
        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login("", "password"));

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login("alice", ""));

        verifyNoInteractions(repository, jwtService);
    }
}
