package mmiv.user_access_service.config;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Initializes a private, configured administrator once, without resetting existing accounts. */
@Component
@Profile("!test")
@DependsOn("userAccessSchema")
@ConditionalOnProperty(name="mmiv.bootstrap-admin.enabled", havingValue="true")
public class BootstrapAdminInitializer implements InitializingBean {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final TransactionTemplate transaction;
  private final String username;
  private final String password;

  public BootstrapAdminInitializer(JdbcTemplate jdbc, PasswordEncoder passwords, PlatformTransactionManager manager,
      @Value("${mmiv.bootstrap-admin.username:admin}") String username,
      @Value("${mmiv.bootstrap-admin.password:}") String password) {
    this.jdbc = jdbc; this.passwords = passwords; this.transaction = new TransactionTemplate(manager);
    this.username = username; this.password = password;
  }

  @Override
  public void afterPropertiesSet() {
    if (username == null || !username.matches("[A-Za-z0-9_.-]{3,40}") || username.matches("mmiv_ci_[0-9a-f]{32}")
        || password == null || password.isBlank() || password.length() < 16 || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalStateException("Bootstrap administrator requires a valid username and a private strong password");
    transaction.executeWithoutResult(status -> {
      jdbc.execute("LOCK TABLE APP_USERS IN EXCLUSIVE MODE WAIT 10");
      jdbc.execute("LOCK TABLE ADMIN_BOOTSTRAP_STATE IN EXCLUSIVE MODE WAIT 10");
      if (jdbc.queryForObject("SELECT COUNT(*) FROM ADMIN_BOOTSTRAP_STATE", Integer.class) != 0
          || jdbc.queryForObject("SELECT COUNT(*) FROM APP_USERS WHERE ROLE='ADMIN'", Integer.class) != 0) return;
      if (jdbc.queryForObject("SELECT COUNT(*) FROM APP_USERS WHERE USERNAME=?", Integer.class, username) != 0)
        throw new IllegalStateException("Bootstrap username is already registered; use an operator invitation instead");
      String id = UUID.randomUUID().toString();
      jdbc.update("INSERT INTO APP_USERS (USER_ID,USERNAME,ROLE,ACTIVE,PASSWORD_HASH) VALUES (?,?,'ADMIN',1,?)",
          id, username, passwords.encode(password));
      // A consumed bootstrap marker prevents automatic recreation after deletion or demotion.
      jdbc.update("INSERT INTO ADMIN_BOOTSTRAP_STATE (BOOTSTRAP_ID,ADMIN_USER_ID,TOKEN_HASH) VALUES (1,?,?)",
          id, "0".repeat(64));
    });
  }
}
