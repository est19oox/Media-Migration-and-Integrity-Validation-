package mmiv.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import mmiv.model.ValidationLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.main.web-application-type=none", "discovery.root=/tmp"})
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_ORACLE_IT", matches = "true")
class ValidationLogRepositoryIntegrationTest {

  @Autowired private ValidationLogRepository repository;

  @Autowired private JdbcTemplate jdbcTemplate;

  private String uniqueFileName() {
    return "it-" + UUID.randomUUID() + ".mp4";
  }

  private Map<String, Object> findLog(String fileName) {

    return jdbcTemplate.queryForMap(
        """
                SELECT
                    LOG_ID,
                    FILE_NAME,
                    FILE_TYPE,
                    FILE_DIR,
                    FILE_SIZE,
                    VALIDATION_STATUS,
                    CHECK_FAILED,
                    EXPECTED_VALUE,
                    CHECKED_AT
                FROM MEDIA_LOG
                WHERE FILE_NAME = ?
                """,
        fileName);
  }

  @Test
  void shouldInsertSuccessLogIntoOracle() {

    String fileName = uniqueFileName();

    ValidationLog log =
        new ValidationLog(
            fileName, "MP4", "videos", 10500000L, "SUCCESS", null, null, LocalDateTime.now());

    assertEquals(1, repository.save(log));

    Map<String, Object> saved = findLog(fileName);

    assertNotNull(saved.get("LOG_ID"));
    assertEquals(fileName, saved.get("FILE_NAME"));
    assertEquals("SUCCESS", saved.get("VALIDATION_STATUS"));
    assertNull(saved.get("CHECK_FAILED"));
    assertNull(saved.get("EXPECTED_VALUE"));
    assertNotNull(saved.get("CHECKED_AT"));
  }

  @Test
  void shouldInsertMismatchLogIntoOracle() {

    String fileName = uniqueFileName();

    ValidationLog log =
        new ValidationLog(
            fileName,
            "MP4",
            "videos",
            19500000L,
            "MISMATCH",
            "FILE_SIZE",
            "18000000",
            LocalDateTime.now());

    assertEquals(1, repository.save(log));

    Map<String, Object> saved = findLog(fileName);

    assertEquals("MISMATCH", saved.get("VALIDATION_STATUS"));
    assertEquals("FILE_SIZE", saved.get("CHECK_FAILED"));
    assertEquals("18000000", saved.get("EXPECTED_VALUE"));
    assertEquals(
        0,
        new java.math.BigDecimal("19500000")
            .compareTo((java.math.BigDecimal) saved.get("FILE_SIZE")));
  }

  @Test
  void shouldInsertMissingFileLogWithNullFields() {

    String fileName = uniqueFileName();

    ValidationLog log =
        new ValidationLog(
            fileName,
            null,
            null,
            null,
            "MISMATCH",
            "FILE_NOT_FOUND",
            fileName,
            LocalDateTime.now());

    assertEquals(1, repository.save(log));

    Map<String, Object> saved = findLog(fileName);

    assertNotNull(saved.get("LOG_ID"));
    assertNull(saved.get("FILE_TYPE"));
    assertNull(saved.get("FILE_DIR"));
    assertNull(saved.get("FILE_SIZE"));
    assertEquals("FILE_NOT_FOUND", saved.get("CHECK_FAILED"));
  }

  @Test
  void shouldGenerateDifferentLogIds() {

    String firstFile = uniqueFileName();
    String secondFile = uniqueFileName();

    repository.save(
        new ValidationLog(
            firstFile, "MP4", "videos", 100L, "SUCCESS", null, null, LocalDateTime.now()));

    repository.save(
        new ValidationLog(
            secondFile, "MP4", "videos", 200L, "SUCCESS", null, null, LocalDateTime.now()));

    Object firstId = findLog(firstFile).get("LOG_ID");
    Object secondId = findLog(secondFile).get("LOG_ID");

    assertNotNull(firstId);
    assertNotNull(secondId);
    assertNotEquals(firstId, secondId);
  }
}
