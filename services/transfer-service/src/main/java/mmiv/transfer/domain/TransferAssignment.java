package mmiv.transfer.domain;

import java.util.UUID;

/** Transfer-owned interpretation of a Metadata assignment. */
public record TransferAssignment(String kind, String sourcePath, UUID verificationRunId) {
  public String sourceForTransfer(UUID requestedRun) {
    if (requestedRun != null && !requestedRun.equals(verificationRunId))
      throw new IllegalArgumentException("Verification transfer must use a plan from the same run");
    if (!"SOURCE".equals(kind) || sourcePath == null)
      throw new IllegalArgumentException("Metadata requires an uploaded file");
    return sourcePath;
  }
}
