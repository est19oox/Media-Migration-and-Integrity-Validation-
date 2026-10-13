package mmiv.dto;

public record ValidationRequest(
    String bucket, String objectKey, String sourceChecksum, String transferId) {
  public ValidationRequest(String bucket, String objectKey, String sourceChecksum) {
    this(bucket, objectKey, sourceChecksum, null);
  }
}
