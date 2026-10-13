package mmiv.user_access_service.service;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import mmiv.user_access_service.dto.SignupRequest;
import mmiv.user_access_service.dto.SignupResponse;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class RegistrationService {
  private final UserRepository users;
  private final PasswordEncoder passwords;

  public RegistrationService(UserRepository users, PasswordEncoder passwords) {
    this.users = users;
    this.passwords = passwords;
  }

  public SignupResponse register(SignupRequest request) {
    if (request == null
        || request.username() == null
        || !request.username().matches("[A-Za-z0-9_.-]{3,40}")
        || request.password() == null
        || request.password().isBlank()
        || request.password().length() < 8
        || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new InvalidSignupException();
    }
    if (users.findByUsername(request.username()).isPresent()) {
      throw new DuplicateUsernameException();
    }
    User user =
        new User(
            UUID.randomUUID().toString(),
            request.username(),
            "USER",
            true,
            Set.of(),
            passwords.encode(request.password()));
    try {
      // INSERT-only: a concurrent duplicate must never overwrite another user's credentials.
      users.create(user);
    } catch (DuplicateKeyException exception) {
      throw new DuplicateUsernameException();
    }
    return new SignupResponse(
        user.getUserId(), user.getUsername(), "Account created. Please sign in.");
  }
}
