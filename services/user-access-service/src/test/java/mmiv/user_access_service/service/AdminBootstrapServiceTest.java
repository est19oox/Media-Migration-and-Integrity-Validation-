package mmiv.user_access_service.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class AdminBootstrapServiceTest {
  private static final String USERNAME = "initial-admin";
  private static final String PASSWORD = "local-bootstrap-password";
  private static final String CONTAINER_QUERY =
      "SELECT SYS_CONTEXT('USERENV', 'CON_NAME') FROM dual";
  private static final String SESSION_USER_QUERY =
      "SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') FROM dual";
  private static final String ADMIN_COUNT_QUERY =
      "SELECT COUNT(*) FROM USER_ACCESS.APP_USERS WHERE ROLE = 'ADMIN'";
  private static final String USERNAME_COUNT_QUERY =
      "SELECT COUNT(*) FROM USER_ACCESS.APP_USERS WHERE USERNAME = ?";
  private static final String LOCK_QUERY =
      "LOCK TABLE USER_ACCESS.APP_USERS IN EXCLUSIVE MODE NOWAIT";

  private JdbcTemplate jdbcTemplate;
  private PasswordEncoder passwordEncoder;
  private AdminBootstrapService bootstrapService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    passwordEncoder = mock(PasswordEncoder.class);
    bootstrapService = new AdminBootstrapService(jdbcTemplate, passwordEncoder);
  }

  @Test
  void refusesToCreateAnotherAdminIncludingWhenAnExistingAdminIsInactive() {
    allowOnlyTheExpectedDatabase();
    when(jdbcTemplate.queryForObject(ADMIN_COUNT_QUERY, Integer.class)).thenReturn(1);

    assertThrows(
        IllegalStateException.class,
        () -> bootstrapService.createInitialAdministrator(USERNAME, PASSWORD));

    verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
    verify(passwordEncoder, never()).encode(any(String.class));
  }

  @Test
  void refusesAConnectionToTheWrongDatabaseBeforeLockingOrInserting() {
    when(jdbcTemplate.queryForObject(CONTAINER_QUERY, String.class)).thenReturn("OTHERPDB");
    when(jdbcTemplate.queryForObject(SESSION_USER_QUERY, String.class)).thenReturn("USER_ACCESS");

    assertThrows(
        IllegalStateException.class,
        () -> bootstrapService.createInitialAdministrator(USERNAME, PASSWORD));

    verify(jdbcTemplate, never()).execute(LOCK_QUERY);
    verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
  }

  @Test
  void refusesAConnectionAsTheWrongSchemaOwnerBeforeLockingOrInserting() {
    when(jdbcTemplate.queryForObject(CONTAINER_QUERY, String.class)).thenReturn("FREEPDB1");
    when(jdbcTemplate.queryForObject(SESSION_USER_QUERY, String.class)).thenReturn("OTHER_SCHEMA");

    assertThrows(
        IllegalStateException.class,
        () -> bootstrapService.createInitialAdministrator(USERNAME, PASSWORD));

    verify(jdbcTemplate, never()).execute(LOCK_QUERY);
    verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
  }

  @Test
  void databaseFailureDuringInsertTriggersTransactionRollback() {
    allowOnlyTheExpectedDatabase();
    when(jdbcTemplate.queryForObject(ADMIN_COUNT_QUERY, Integer.class)).thenReturn(0);
    when(jdbcTemplate.queryForObject(eq(USERNAME_COUNT_QUERY), eq(Integer.class), eq(USERNAME)))
        .thenReturn(0);
    when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
    when(jdbcTemplate.update(any(String.class), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("insert failure"));

    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    ProxyFactory proxyFactory = new ProxyFactory(bootstrapService);
    proxyFactory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    AdminBootstrapService transactionalService = (AdminBootstrapService) proxyFactory.getProxy();

    assertThrows(
        DataAccessResourceFailureException.class,
        () -> transactionalService.createInitialAdministrator(USERNAME, PASSWORD));

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  private void allowOnlyTheExpectedDatabase() {
    when(jdbcTemplate.queryForObject(CONTAINER_QUERY, String.class)).thenReturn("FREEPDB1");
    when(jdbcTemplate.queryForObject(SESSION_USER_QUERY, String.class)).thenReturn("USER_ACCESS");
    when(jdbcTemplate.queryForObject(ADMIN_COUNT_QUERY, Integer.class)).thenReturn(0);
    when(jdbcTemplate.queryForObject(eq(USERNAME_COUNT_QUERY), eq(Integer.class), eq(USERNAME)))
        .thenReturn(0);
    when(passwordEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
  }
}
