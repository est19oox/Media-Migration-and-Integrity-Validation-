package mmiv.transfer.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetadataClientBatchValidationTest {
  @Test
  void acceptsTheRequestedPathAndDeterministicCollisionSuffix() {
    var request = new MetadataClient.AllocationFile("report.pdf", 12, "term/report.pdf");

    assertTrue(MetadataClient.matchesAllocation(plan("term/report.pdf", 12), request));
    assertTrue(MetadataClient.matchesAllocation(plan("term/report (2).pdf", 12), request));
    assertTrue(MetadataClient.matchesAllocation(plan("term/report (20).pdf", 12), request));
  }

  @Test
  void rejectsReorderedOrAlteredAssignmentsBeforeUpload() {
    var request = new MetadataClient.AllocationFile("report.pdf", 12, "term/report.pdf");

    assertFalse(MetadataClient.matchesAllocation(plan("other/report.pdf", 12), request));
    assertFalse(MetadataClient.matchesAllocation(plan("term/other.pdf", 12), request));
    assertFalse(MetadataClient.matchesAllocation(plan("term/report.pdf", 13), request));
    assertFalse(MetadataClient.matchesAllocation(plan("term/report (1).pdf", 12), request));
  }

  private MetadataClient.Plan plan(String path, long size) {
    UUID metadata = UUID.randomUUID();
    return new MetadataClient.Plan(
        metadata,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "owner",
        "landing",
        "bucket",
        "landing/migrations/" + metadata + "/" + path,
        null,
        path.substring(path.lastIndexOf('/') + 1),
        "application/pdf",
        size,
        "UPLOAD",
        path);
  }
}
