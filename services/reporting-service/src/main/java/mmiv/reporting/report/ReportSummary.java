package mmiv.reporting.report;

public record ReportSummary(
    long totalFiles,
    long discoveryAccepted,
    long discoveryFailed,
    long transferStored,
    long transferFailed,
    long integrityPassed,
    long integrityFailed,
    long successfulFiles,
    long failedFiles,
    double successRate) {

  public ReportSummary {
    requireNonNegative(totalFiles, "totalFiles");
    requireNonNegative(discoveryAccepted, "discoveryAccepted");
    requireNonNegative(discoveryFailed, "discoveryFailed");
    requireNonNegative(transferStored, "transferStored");
    requireNonNegative(transferFailed, "transferFailed");
    requireNonNegative(integrityPassed, "integrityPassed");
    requireNonNegative(integrityFailed, "integrityFailed");
    requireNonNegative(successfulFiles, "successfulFiles");
    requireNonNegative(failedFiles, "failedFiles");

    if (!Double.isFinite(successRate) || successRate < 0.0 || successRate > 100.0) {
      throw new IllegalArgumentException("successRate must be between 0 and 100");
    }
  }

  public static ReportSummary zero() {
    return new ReportSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0);
  }

  private static void requireNonNegative(long value, String fieldName) {
    if (value < 0) {
      throw new IllegalArgumentException(fieldName + " must not be negative");
    }
  }
}
