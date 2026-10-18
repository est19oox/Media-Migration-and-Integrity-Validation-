package mmiv.transfer.domain;

import static mmiv.transfer.domain.Transfer.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TransferTest {
  private Transfer stored() {
    var transfer = new Transfer(UUID.randomUUID());
    transfer.recordStorage(new StoredObject("bucket", "folder/file.txt", "etag", "version"));
    return transfer;
  }

  @Test
  void completionRequiresStoredBytesAndSuccessfulValidation() {
    var transfer = new Transfer(UUID.randomUUID());
    var passed = new Validation(ValidationStatus.PASSED, "Verified");
    assertFalse(transfer.success());
    assertThrows(IllegalStateException.class, () -> transfer.recordValidation(passed));
    transfer.recordStorage(new StoredObject("bucket", "key", null, null));
    assertFalse(transfer.success());
    transfer.recordValidation(passed);
    assertTrue(transfer.success());
    assertEquals(State.COMPLETED, transfer.state());
    assertEquals(CleanupStatus.NOT_REQUIRED, transfer.cleanup().status());
    assertEquals("Verified. No cleanup required", transfer.message());
    assertFalse(transfer.requiresCleanup());
    assertThrows(
        IllegalStateException.class,
        () -> transfer.recordCleanup(new Cleanup(CleanupStatus.DELETED, "Deleted")));
  }

  @ParameterizedTest
  @EnumSource(
      value = ValidationStatus.class,
      names = {"FAILED", "UNAVAILABLE"})
  void unsuccessfulValidationRequiresCleanupAndNeverBecomesSuccess(ValidationStatus status) {
    var transfer = stored();
    transfer.recordValidation(new Validation(status, "Unconfirmed"));
    assertTrue(transfer.requiresCleanup());
    assertFalse(transfer.success());
    assertThrows(IllegalStateException.class, transfer::cleanup);
    assertThrows(
        IllegalArgumentException.class,
        () -> transfer.recordCleanup(new Cleanup(CleanupStatus.NOT_REQUIRED, "Skipped")));
    assertEquals(State.CLEANUP_REQUIRED, transfer.state());
    transfer.recordCleanup(new Cleanup(CleanupStatus.DELETED, "Deleted"));
    assertEquals(State.CLEANED_UP, transfer.state());
    assertFalse(transfer.success());
    assertEquals(status, transfer.validation().status());
    assertThrows(
        IllegalStateException.class,
        () -> transfer.recordValidation(new Validation(ValidationStatus.PASSED, "Late success")));
  }

  @Test
  void failedCleanupIsTerminalAndRetainsExactObjectIdentity() {
    var transfer = stored();
    assertEquals("version", transfer.stored().versionId());
    assertEquals("etag", transfer.stored().eTag());
    transfer.recordValidation(new Validation(ValidationStatus.UNAVAILABLE, "Unavailable"));
    transfer.recordCleanup(new Cleanup(CleanupStatus.DELETE_FAILED, "Manual cleanup required"));
    assertEquals(State.CLEANUP_FAILED, transfer.state());
    assertFalse(transfer.success());
    assertFalse(transfer.requiresCleanup());
    assertEquals("Unavailable. Manual cleanup required", transfer.message());
    assertThrows(
        IllegalStateException.class,
        () -> transfer.recordCleanup(new Cleanup(CleanupStatus.DELETED, "Deleted")));
  }

  @Test
  void storageCannotBeReplacedAndCleanupCannotPrecedeValidation() {
    var transfer = stored();
    assertThrows(
        IllegalStateException.class,
        () -> transfer.recordStorage(new StoredObject("other", "other", null, null)));
    assertThrows(
        IllegalStateException.class,
        () -> transfer.recordCleanup(new Cleanup(CleanupStatus.DELETED, "Deleted")));
    assertEquals("bucket", transfer.stored().bucket());
    assertEquals(State.STORED, transfer.state());
  }
}
