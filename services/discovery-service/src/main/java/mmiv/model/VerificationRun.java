package mmiv.model;

import java.util.UUID;

/** Tombstone aggregate retained after test data is removed to reject late writes. */
public record VerificationRun(String ownerId, UUID runId, boolean closed) {
  public VerificationRun close() {
    return new VerificationRun(ownerId, runId, true);
  }

  public boolean acceptsPlans() {
    return !closed;
  }
}
