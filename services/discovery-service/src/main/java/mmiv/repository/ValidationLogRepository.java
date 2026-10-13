package mmiv.repository;

import java.sql.Timestamp;
import java.sql.Types;
import mmiv.model.ValidationLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ValidationLogRepository {

  private final JdbcTemplate jdbcTemplate;

  public ValidationLogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Save one validation log into Oracle MEDIA_LOG.
   *
   * <p>LOG_ID is generated automatically by Oracle.
   */
  public int save(ValidationLog log) {

    String sql =
        """
                INSERT INTO MEDIA_LOG
                (
                    FILE_NAME,
                    FILE_TYPE,
                    FILE_DIR,
                    FILE_SIZE,
                    VALIDATION_STATUS,
                    CHECK_FAILED,
                    EXPECTED_VALUE,
                    CHECKED_AT
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

    Timestamp checkedAt = log.checkedAt() == null ? null : Timestamp.valueOf(log.checkedAt());

    return jdbcTemplate.update(
        sql,
        new Object[] {
          log.fileName(),
          log.fileType(),
          log.fileDir(),
          log.fileSize(),
          log.validationStatus(),
          log.checkFailed(),
          log.expectedValue(),
          checkedAt
        },
        new int[] {
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.NUMERIC,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.TIMESTAMP
        });
  }
}
