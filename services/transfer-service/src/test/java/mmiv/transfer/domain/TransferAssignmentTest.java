package mmiv.transfer.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferAssignmentTest {
  @Test
  void sourcePlanCanBeTransferredOnlyForItsVerificationRun() {
    UUID run = UUID.randomUUID();
    var assignment = new TransferAssignment("SOURCE", "media/file.txt", run);
    assertEquals("media/file.txt", assignment.sourceForTransfer(run));
    assertThrows(
        IllegalArgumentException.class, () -> assignment.sourceForTransfer(UUID.randomUUID()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TransferAssignment("UPLOAD", null, run).sourceForTransfer(run));
  }
}
