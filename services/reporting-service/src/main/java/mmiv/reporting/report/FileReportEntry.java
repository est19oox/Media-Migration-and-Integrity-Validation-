package mmiv.reporting.report;

import java.util.Objects;
import java.util.UUID;

public class FileReportEntry {

  private final String fileId;

  private String discoveryStatus;

  private String manifestMediaId;

  private String fileName;

  private String relativePath;

  private String transferStatus;

  private String integrityStatus;

  private FileFinalStatus finalStatus;

  private FailureView failure;

  private FileReportEntry(
      String fileId,
      String discoveryStatus,
      String manifestMediaId,
      String fileName,
      String relativePath,
      String transferStatus,
      String integrityStatus,
      FileFinalStatus finalStatus,
      FailureView failure) {
    this.fileId = Objects.requireNonNull(fileId, "fileId must not be null");

    this.discoveryStatus = discoveryStatus;
    this.manifestMediaId = manifestMediaId;
    this.fileName = fileName;
    this.relativePath = relativePath;
    this.transferStatus = transferStatus;
    this.integrityStatus = integrityStatus;

    this.finalStatus = Objects.requireNonNull(finalStatus, "finalStatus must not be null");

    this.failure = failure;
  }

  public static FileReportEntry processing(UUID fileId) {
    Objects.requireNonNull(fileId, "fileId must not be null");

    return new FileReportEntry(
        fileId.toString(), null, null, null, null, null, null, FileFinalStatus.PROCESSING, null);
  }

  public void recordDiscoveryAccepted() {
    this.discoveryStatus = "ACCEPTED";
  }

  public void recordDiscoveryMetadata(
      String manifestMediaId, String fileName, String relativePath) {
    this.manifestMediaId = manifestMediaId;
    this.fileName = Objects.requireNonNull(fileName, "fileName must not be null");
    this.relativePath = relativePath;
  }

  public void recordDiscoveryFailed(FailureView failure) {
    recordDiscoveryFailed("REJECTED", failure);
  }

  public void recordDiscoveryFailed(String status, FailureView failure) {
    if (!"REJECTED".equals(status) && !"ERROR".equals(status)) {
      throw new IllegalArgumentException("Unsupported discovery failure status: " + status);
    }

    this.discoveryStatus = status;
    markFailed(failure);
  }

  public void recordTransferStored() {
    this.transferStatus = "STORED";
  }

  public void recordTransferFailed(FailureView failure) {
    this.transferStatus = "FAILED";
    markFailed(failure);
  }

  public void recordIntegrityPassed() {
    this.integrityStatus = "PASSED";
    markSucceeded();
  }

  public void recordIntegrityFailed(FailureView failure) {
    recordIntegrityFailed("FAILED", failure);
  }

  public void recordIntegrityFailed(String status, FailureView failure) {
    if (!"FAILED".equals(status) && !"ERROR".equals(status)) {
      throw new IllegalArgumentException("Unsupported integrity failure status: " + status);
    }

    this.integrityStatus = status;
    markFailed(failure);
  }

  public void markProcessing() {
    this.finalStatus = FileFinalStatus.PROCESSING;
    this.failure = null;
  }

  public void markSucceeded() {
    this.finalStatus = FileFinalStatus.SUCCEEDED;
    this.failure = null;
  }

  public void markFailed(FailureView failure) {
    this.finalStatus = FileFinalStatus.FAILED;

    this.failure = Objects.requireNonNull(failure, "failure must not be null");
  }

  public String getFileId() {
    return fileId;
  }

  public String getDiscoveryStatus() {
    return discoveryStatus;
  }

  public String getManifestMediaId() {
    return manifestMediaId;
  }

  public String getFileName() {
    return fileName;
  }

  public String getRelativePath() {
    return relativePath;
  }

  public String getTransferStatus() {
    return transferStatus;
  }

  public String getIntegrityStatus() {
    return integrityStatus;
  }

  public FileFinalStatus getFinalStatus() {
    return finalStatus;
  }

  public FailureView getFailure() {
    return failure;
  }
}
