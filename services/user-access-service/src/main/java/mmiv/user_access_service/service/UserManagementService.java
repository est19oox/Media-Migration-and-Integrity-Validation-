package mmiv.user_access_service.service;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import mmiv.user_access_service.dto.CreateUserRequest;
import mmiv.user_access_service.dto.UserResponse;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserManagementService {
  private static final Logger logger = LoggerFactory.getLogger(UserManagementService.class);

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public UserManagementService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public UserResponse createUser(CreateUserRequest request, String administratorId) {
    try {
      requireAdministrator(administratorId);
      if (!"USER".equals(request.role())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Only USER accounts may be created");
      }
      String username = request.username().strip();
      if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is too long");
      }
      if (userRepository.findByUsername(username).isPresent()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is unavailable");
      }

      User user =
          new User(
              UUID.randomUUID().toString(),
              username,
              "USER",
              true,
              Set.of(),
              passwordEncoder.encode(request.password()));
      userRepository.save(user);
      return toResponse(user);
    } catch (DataIntegrityViolationException exception) {
      logger.warn("User creation failed due to a data integrity constraint");
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is unavailable");
    } catch (DataAccessException exception) {
      logger.error("User creation failed because the repository was unavailable");
      throw repositoryFailure();
    }
  }

  @Transactional
  public UserResponse setUserActive(String userId, boolean active, String administratorId) {
    try {
      requireAdministrator(administratorId);
      if (!active && administratorId.equals(userId)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Administrators cannot deactivate their own account");
      }

      User existing =
          userRepository
              .findById(userId)
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
      if (active && !existing.isActive() && "ADMIN".equals(existing.getRole())) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Administrator reactivation requires an approved operator recovery procedure");
      }
      User updated =
          new User(
              existing.getUserId(),
              existing.getDestinationAccesses(),
              existing.getUsername(),
              existing.getRole(),
              active,
              existing.getPermissions(),
              existing.getPasswordHash());
      userRepository.save(updated);
      return toResponse(updated);
    } catch (DataIntegrityViolationException exception) {
      logger.error("User activity update failed due to a data integrity constraint");
      throw repositoryFailure();
    } catch (DataAccessException exception) {
      logger.error("User activity update failed because the repository was unavailable");
      throw repositoryFailure();
    }
  }

  private void requireAdministrator(String subject) {
    if (subject == null || subject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
    }
    User requester =
        userRepository
            .findById(subject)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Administrator access required"));
    if (!requester.isActive() || !"ADMIN".equals(requester.getRole())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
    }
  }

  private static UserResponse toResponse(User user) {
    return new UserResponse(user.getUserId(), user.getUsername(), user.getRole(), user.isActive());
  }

  private static ResponseStatusException repositoryFailure() {
    return new ResponseStatusException(
        HttpStatus.INTERNAL_SERVER_ERROR, "Unable to process user management request");
  }
}
