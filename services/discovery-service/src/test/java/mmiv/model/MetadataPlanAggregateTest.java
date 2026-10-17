package mmiv.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetadataPlanAggregateTest {
  private MetadataPlan plan(String path) {
    return MetadataPlan.assign(
        "owner", "SW001", "bucket", null, "report.pdf", 10, true, path, null);
  }

  @Test
  void duplicateNamesAreResolvedOnlyWithinTheirLogicalDirectory() {
    var plan = plan("semester/report.pdf");
    var named =
        plan.reserveAvailableName(
            Set.of("semester/report.pdf", "semester/report (2).pdf", "other/report (3).pdf"));
    assertEquals("semester/report (3).pdf", named.objectPath());
    assertEquals("report (3).pdf", named.fileName());
    assertEquals(plan.metadataId(), named.metadataId());
    assertEquals(plan.ownerId(), named.ownerId());
    assertEquals(
        "SW001/migrations/" + plan.metadataId() + "/semester/report (3).pdf",
        named.destinationKey());
    assertEquals("semester/report.pdf", plan.objectPath());
  }

  @Test
  void unsafePathsAndUnsupportedMediaCannotBecomeAssignments() {
    assertThrows(IllegalArgumentException.class, () -> plan("../report.pdf"));
    assertThrows(IllegalArgumentException.class, () -> plan("semester/different.pdf"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MetadataPlan.assign("owner", "SW001", "bucket", null, "run.exe", 10, true, null, null));
  }

  @Test
  void closedVerificationRunCannotAcceptLatePlans() {
    var run = new VerificationRun("owner", UUID.randomUUID(), false);
    assertTrue(run.acceptsPlans());
    assertFalse(run.close().acceptsPlans());
    assertEquals(run.close(), run.close().close());
  }
}
