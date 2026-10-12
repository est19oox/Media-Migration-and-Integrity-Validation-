package mmiv.user_access_service.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import mmiv.user_access_service.model.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "admin.bootstrap", name = "enabled", havingValue = "true")
public class AdminBootstrapService {
  private final JdbcTemplate jdbcTemplate;
  private final PasswordEncoder passwordEncoder;

  public AdminBootstrapService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
    this.jdbcTemplate = jdbcTemplate;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public String createInitialAdministrator(String username, String password) {
    String normalizedUsername = username == null ? "" : username.strip();
    if (normalizedUsername.isBlank() || normalizedUsername.length() > 100) {
      throw new IllegalArgumentException("A username of 1 to 100 characters is required.");
    }
    if (password == null
        || password.isBlank()
        || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new IllegalArgumentException(
          "A non-blank password of at most 72 UTF-8 bytes is required.");
    }

    String container =
        jdbcTemplate.queryForObject(
            "SELECT SYS_CONTEXT('USERENV', 'CON_NAME') FROM dual", String.class);
    String sessionUser =
        jdbcTemplate.queryForObject(
            "SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') FROM dual", String.class);
    if (!"FREEPDB1".equals(container) || !"USER_ACCESS".equals(sessionUser)) {
      throw new IllegalStateException("Bootstrap is restricted to USER_ACCESS on FREEPDB1.");
    }

    jdbcTemplate.execute("LOCK TABLE USER_ACCESS.APP_USERS IN EXCLUSIVE MODE NOWAIT");
    Integer administratorCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM USER_ACCESS.APP_USERS WHERE ROLE = 'ADMIN'", Integer.class);
    if (administratorCount == null || administratorCount != 0) {
      throw new IllegalStateException(
          "An ADMIN account already exists; bootstrap will not modify or reactivate accounts.");
    }

    Integer usernameCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM USER_ACCESS.APP_USERS WHERE USERNAME = ?",
            Integer.class,
            normalizedUsername);
    if (usernameCount == null || usernameCount != 0) {
      throw new IllegalStateException(
          "That username already exists; bootstrap will not modify users.");
    }

    String userId = UUID.randomUUID().toString();
    User administrator =
        new User(
            userId,
            normalizedUsername,
            "ADMIN",
            true,
            java.util.Set.of(),
            passwordEncoder.encode(password));
    jdbcTemplate.update(
        """
            INSERT INTO USER_ACCESS.APP_USERS
                (USER_ID, USERNAME, ROLE, ACTIVE, PASSWORD_HASH)
            VALUES (?, ?, 'ADMIN', 1, ?)
            """,
        administrator.getUserId(),
        administrator.getUsername(),
        administrator.getPasswordHash());
    return userId;
  }
}
