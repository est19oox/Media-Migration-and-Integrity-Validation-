package mmiv.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import mmiv.csv.MetadataCsvRecord;
import mmiv.csv.MetadataCsvService;
import mmiv.model.ValidationLog;
import mmiv.repository.ValidationLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.main.web-application-type=none", "discovery.root=/tmp"})
@EnabledIfEnvironmentVariable(named = "RUN_ORACLE_IT", matches = "true")
class ManifestTransactionRollbackIntegrationTest {

  @TempDir Path tempDir;

  @Autowired private ManifestProcessingService processingService;

  @Autowired private MetadataCsvService csvService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoSpyBean private ValidationLogRepository logRepository;

  /**
   * Test 1: Verify that Spring TransactionTemplate rolls back an earlier successful insert when a
   * later insert fails.
   */
  @Test
  void shouldRollbackAllLogsWhenSecondInsertFails() {

    String firstFile = "rollback-first-" + UUID.randomUUID() + ".mp4";

    String secondFile = "rollback-second-" + UUID.randomUUID() + ".mp4";

    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    assertThrows(
        DataAccessException.class,
        () ->
            transactionTemplate.execute(
                status -> {
                  logRepository.save(
                      new ValidationLog(
                          firstFile,
                          "MP4",
                          "videos",
                          100L,
                          "SUCCESS",
                          null,
                          null,
                          LocalDateTime.now()));

                  // Exceeds MEDIA_LOG.VALIDATION_STATUS
                  // VARCHAR2(20), causing a real Oracle error.
                  logRepository.save(
                      new ValidationLog(
                          secondFile,
                          "MP4",
                          "videos",
                          200L,
                          "INVALID_VALIDATION_STATUS_FOR_ROLLBACK_TEST",
                          null,
                          null,
                          LocalDateTime.now()));

                  return null;
                }));

    assertEquals(0, countLogs(firstFile));
    assertEquals(0, countLogs(secondFile));
  }

  /**
   * Test 2: Verify that ManifestProcessingService's @Transactional rolls back both MEDIA_LOG and
   * FILE_METADATA when processing fails midway.
   *
   * <p>This test class intentionally has no @Transactional.
   */
  @Test
  void shouldRollbackMetadataAndLogsWhenProcessingFails() throws Exception {

    String unique = UUID.randomUUID().toString();

    String firstFile = "rollback-first-" + unique + ".mp4";

    String secondFile = "rollback-second-" + unique + ".mp4";

    // Create real files for Discovery.
    Path root = tempDir.resolve("business-rollback");
    Files.createDirectories(root);

    Path firstPath = root.resolve(firstFile);
    Path secondPath = root.resolve(secondFile);

    Files.writeString(firstPath, "first-video", StandardCharsets.UTF_8);

    Files.writeString(secondPath, "second-video", StandardCharsets.UTF_8);

    // Generate a valid nine-column CSV manifest.
    Path csvPath = tempDir.resolve("business-rollback.csv");

    List<MetadataCsvRecord> records =
        List.of(
            new MetadataCsvRecord(
                "RB001",
                firstFile,
                "VIDEO",
                "MP4",
                Files.size(firstPath),
                LocalDate.now(),
                "integration-test",
                "rollback",
                "First file"),
            new MetadataCsvRecord(
                "RB002",
                secondFile,
                "VIDEO",
                "MP4",
                Files.size(secondPath),
                LocalDate.now(),
                "integration-test",
                "rollback",
                "Second file"));

    csvService.generateCsv(csvPath, records);

    // First log uses the real Oracle repository.
    // Second log throws a controlled exception.
    doAnswer(
            invocation -> {
              ValidationLog log = invocation.getArgument(0);

              if (secondFile.equals(log.fileName())) {
                throw new DataIntegrityViolationException(
                    "Simulated database failure for second file");
              }

              return invocation.callRealMethod();
            })
        .when(logRepository)
        .save(any(ValidationLog.class));

    try {

      assertThrows(
          DataIntegrityViolationException.class,
          () -> processingService.processManifest(csvPath, root));

      // First file's writes must be rolled back.
      assertEquals(0, countLogs(firstFile));
      assertEquals(0, countMetadata(firstFile));

      // Second file must not be persisted.
      assertEquals(0, countLogs(secondFile));
      assertEquals(0, countMetadata(secondFile));

      // Verify both log saves were attempted.
      verify(logRepository, times(2)).save(any(ValidationLog.class));

    } finally {

      // Remove test-specific spy behavior.
      reset(logRepository);
    }
  }

  /** Count validation logs for a specific file. */
  private int countLogs(String fileName) {

    Integer count =
        jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM MEDIA_LOG
                WHERE FILE_NAME = ?
                """,
            Integer.class,
            fileName);

    return count == null ? 0 : count;
  }

  /** Count metadata records for a specific file. */
  private int countMetadata(String fileName) {

    Integer count =
        jdbcTemplate.queryForObject(
            """
                SELECT COUNT(*)
                FROM FILE_METADATA
                WHERE FILE_NAME = ?
                """,
            Integer.class,
            fileName);

    return count == null ? 0 : count;
  }
}
