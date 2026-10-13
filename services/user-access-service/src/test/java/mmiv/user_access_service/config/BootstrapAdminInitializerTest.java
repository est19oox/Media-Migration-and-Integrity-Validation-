package mmiv.user_access_service.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class BootstrapAdminInitializerTest {
  JdbcTemplate jdbc = mock(JdbcTemplate.class);
  PasswordEncoder passwords = mock(PasswordEncoder.class);
  PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

  BootstrapAdminInitializer initializer() {
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    return new BootstrapAdminInitializer(
        jdbc, passwords, manager, "admin", "a-private-generated-password");
  }

  @Test
  void existingMarkerPreventsPasswordResetOrAccountRecreation() {
    when(jdbc.queryForObject("SELECT COUNT(*) FROM ADMIN_BOOTSTRAP_STATE", Integer.class))
        .thenReturn(1);
    initializer().afterPropertiesSet();
    verifyNoInteractions(passwords);
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void existingAdministratorPreventsBootstrap() {
    when(jdbc.queryForObject("SELECT COUNT(*) FROM ADMIN_BOOTSTRAP_STATE", Integer.class))
        .thenReturn(0);
    when(jdbc.queryForObject("SELECT COUNT(*) FROM APP_USERS WHERE ROLE='ADMIN'", Integer.class))
        .thenReturn(1);
    initializer().afterPropertiesSet();
    verifyNoInteractions(passwords);
  }

  @Test
  void rejectsOccupiedOrdinaryUsernameRatherThanPromotingIt() {
    when(jdbc.queryForObject("SELECT COUNT(*) FROM ADMIN_BOOTSTRAP_STATE", Integer.class))
        .thenReturn(0);
    when(jdbc.queryForObject("SELECT COUNT(*) FROM APP_USERS WHERE ROLE='ADMIN'", Integer.class))
        .thenReturn(0);
    when(jdbc.queryForObject(
            "SELECT COUNT(*) FROM APP_USERS WHERE USERNAME=?", Integer.class, "admin"))
        .thenReturn(1);
    assertThrows(IllegalStateException.class, () -> initializer().afterPropertiesSet());
    verifyNoInteractions(passwords);
  }

  @Test
  void firstStartupHashesPasswordAndRecordsDurableBootstrapHistory() {
    when(jdbc.queryForObject("SELECT COUNT(*) FROM ADMIN_BOOTSTRAP_STATE", Integer.class))
        .thenReturn(0);
    when(jdbc.queryForObject("SELECT COUNT(*) FROM APP_USERS WHERE ROLE='ADMIN'", Integer.class))
        .thenReturn(0);
    when(jdbc.queryForObject(
            "SELECT COUNT(*) FROM APP_USERS WHERE USERNAME=?", Integer.class, "admin"))
        .thenReturn(0);
    when(passwords.encode("a-private-generated-password")).thenReturn("encoded-hash");
    initializer().afterPropertiesSet();
    verify(jdbc)
        .update(startsWith("INSERT INTO APP_USERS"), anyString(), eq("admin"), eq("encoded-hash"));
    verify(jdbc)
        .update(startsWith("INSERT INTO ADMIN_BOOTSTRAP_STATE"), anyString(), eq("0".repeat(64)));
    verify(manager).commit(any());
  }

  @Test
  void weakBootstrapPasswordIsRejectedBeforeAnyDatabaseOperation() {
    for (String password : new String[] {"password", " ".repeat(16), "é".repeat(37)}) {
      var initializer = new BootstrapAdminInitializer(jdbc, passwords, manager, "admin", password);
      assertThrows(IllegalStateException.class, initializer::afterPropertiesSet);
    }
    verifyNoInteractions(jdbc, passwords);
  }
}
