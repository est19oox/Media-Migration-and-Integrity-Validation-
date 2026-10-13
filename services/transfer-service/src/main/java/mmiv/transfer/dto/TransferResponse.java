package mmiv.transfer.dto;

public record TransferResponse(
    String bucket,
    String key,
    String mediaType,
    long sizeBytes,
    String fileId,
    String sha256,
    String transferId,
    String validationResult,
    boolean success,
    String message,
    S3ObjectReference objectReference,
    String cleanupStatus) {
  public TransferResponse(
      String bucket,
      String key,
      String mediaType,
      long sizeBytes,
      String fileId,
      String sha256,
      String transferId,
      String validationResult,
      boolean success,
      String message) {
    this(
        bucket,
        key,
        mediaType,
        sizeBytes,
        fileId,
        sha256,
        transferId,
        validationResult,
        success,
        message,
        null,
        "NOT_ATTEMPTED");
  }

  public TransferResponse(String bucket, String key, String mediaType, long sizeBytes) {
    this(bucket, key, mediaType, sizeBytes, mmiv.transfer.files.FileIds.encode(key));
  }

  public TransferResponse(
      String bucket, String key, String mediaType, long sizeBytes, String fileId) {
    this(
        bucket,
        key,
        mediaType,
        sizeBytes,
        fileId,
        null,
        null,
        "NOT_REQUESTED",
        false,
        "Uploaded; integrity has not been checked");
  }
}
