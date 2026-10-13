package mmiv.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import mmiv.model.ValidationLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ValidationLogRepositoryTest {

  private JdbcTemplate jdbcTemplate;
  private ValidationLogRepository repository;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    repository = new ValidationLogRepository(jdbcTemplate);
  }

  @Test
  void shouldSaveSuccessfulValidationLog() {

    ValidationLog log =
        new ValidationLog(
            "video01.mp4",
            "MP4",
            "videos",
            10500000L,
            "SUCCESS",
            null,
            null,
            LocalDateTime.of(2026, 10, 8, 14, 0));

    when(jdbcTemplate.update(anyString(), any(Object[].class), any(int[].class))).thenReturn(1);

    int inserted = repository.save(log);

    assertEquals(1, inserted);

    verify(jdbcTemplate)
        .update(
            contains("INSERT INTO MEDIA_LOG"),
            argThat(
                (Object[] values) ->
                    values.length == 8
                        && "video01.mp4".equals(values[0])
                        && "SUCCESS".equals(values[4])
                        && values[5] == null
                        && values[6] == null
                        && values[7] instanceof Timestamp),
            argThat(
                (int[] types) ->
                    types.length == 8 && types[3] == Types.NUMERIC && types[7] == Types.TIMESTAMP));
  }

  @Test
  void shouldSaveMismatchValidationLog() {

    ValidationLog log =
        new ValidationLog(
            "video02.mp4",
            "MP4",
            "videos",
            19500000L,
            "MISMATCH",
            "FILE_SIZE",
            "18000000",
            LocalDateTime.of(2026, 10, 8, 14, 5));

    when(jdbcTemplate.update(anyString(), any(Object[].class), any(int[].class))).thenReturn(1);

    int inserted = repository.save(log);

    assertEquals(1, inserted);

    verify(jdbcTemplate)
        .update(
            contains("INSERT INTO MEDIA_LOG"),
            argThat(
                (Object[] values) ->
                    "MISMATCH".equals(values[4])
                        && "FILE_SIZE".equals(values[5])
                        && "18000000".equals(values[6])),
            any(int[].class));
  }

  @Test
  void shouldSaveMissingFileWithNullableFields() {

    ValidationLog log =
        new ValidationLog(
            "missing.mp4",
            null,
            null,
            null,
            "MISMATCH",
            "FILE_NOT_FOUND",
            "missing.mp4",
            LocalDateTime.now());

    when(jdbcTemplate.update(anyString(), any(Object[].class), any(int[].class))).thenReturn(1);

    int inserted = repository.save(log);

    assertEquals(1, inserted);

    verify(jdbcTemplate)
        .update(
            contains("INSERT INTO MEDIA_LOG"),
            argThat(
                (Object[] values) ->
                    "missing.mp4".equals(values[0])
                        && values[1] == null
                        && values[2] == null
                        && values[3] == null
                        && "FILE_NOT_FOUND".equals(values[5])),
            any(int[].class));
  }
}
