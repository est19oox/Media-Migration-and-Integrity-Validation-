package mmiv.user_access_service.service;

import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "admin.bootstrap", name = "enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {
  private static final String CONFIRMATION = "FREEPDB1.USER_ACCESS";

  private final AdminBootstrapService bootstrapService;

  public AdminBootstrapRunner(AdminBootstrapService bootstrapService) {
    this.bootstrapService = bootstrapService;
  }

  @Override
  public void run(ApplicationArguments args) {
    java.io.Console console = System.console();
    if (console == null) {
      throw new IllegalStateException("Admin bootstrap requires an interactive console.");
    }

    console.printf(
        "This one-time operation may create one ADMIN account in FREEPDB1.USER_ACCESS.%n"
            + "It will not change any existing users or assignments.%n");
    String confirmation =
        console.readLine("Type %s to confirm the database and schema: ", CONFIRMATION);
    if (!CONFIRMATION.equals(confirmation)) {
      throw new IllegalStateException(
          "Bootstrap confirmation did not match; no account was created.");
    }

    String username = console.readLine("Initial administrator username: ");
    char[] password = console.readPassword("Initial administrator password: ");
    if (password == null) {
      throw new IllegalStateException("Unable to read the initial administrator password.");
    }
    char[] confirmationPassword = console.readPassword("Confirm initial administrator password: ");
    if (confirmationPassword == null) {
      Arrays.fill(password, '\0');
      throw new IllegalStateException("Unable to read password confirmation.");
    }
    try {
      if (!Arrays.equals(password, confirmationPassword)) {
        throw new IllegalStateException("Password entries did not match; no account was created.");
      }
      String userId = bootstrapService.createInitialAdministrator(username, new String(password));
      console.printf("Initial ADMIN account created. User ID: %s%n", userId);
      console.printf(
          "Verify in FREEPDB1 with USER_ACCESS-qualified queries for ROLE='ADMIN' and ACTIVE=1.%n");
    } finally {
      Arrays.fill(password, '\0');
      Arrays.fill(confirmationPassword, '\0');
    }
  }
}
