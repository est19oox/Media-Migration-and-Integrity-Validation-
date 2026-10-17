package mmiv.entity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class IntegrityValidationAggregateTest {
  @Test
  void comparisonOwnsResultAndCannotBeOverwritten() {
    var validation = IntegrityValidationLog.start("bucket", "key", "ab".repeat(32));
    validation.compareWith("AB".repeat(32), LocalDateTime.now());
    assertTrue(validation.passed());
    assertEquals("PASSED", validation.getValidationStatus());
    assertThrows(
        IllegalStateException.class, () -> validation.fail("late failure", LocalDateTime.now()));
    assertThrows(
        IllegalStateException.class,
        () -> validation.compareWith("cd".repeat(32), LocalDateTime.now()));
  }

  @Test
  void mismatchedBytesAndUnavailableBytesHaveDistinctNonSuccessfulOutcomes() {
    var mismatch = IntegrityValidationLog.start("bucket", "key", "a".repeat(64));
    mismatch.compareWith("b".repeat(64), LocalDateTime.now());
    assertFalse(mismatch.passed());
    assertEquals("FAILED", mismatch.getValidationStatus());
    var error = IntegrityValidationLog.start("bucket", "key", "a".repeat(64));
    error.fail("unavailable", LocalDateTime.now());
    assertFalse(error.passed());
    assertEquals("ERROR", error.getValidationStatus());
    assertNull(error.getDestinationChecksum());
  }
}
