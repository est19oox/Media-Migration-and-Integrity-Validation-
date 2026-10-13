package mmiv.model;

import mmiv.csv.MetadataCsvRecord;

public record ManifestMatchResult(
    MetadataCsvRecord manifestRecord,
    MediaMetadata actualMetadata,
    String validationStatus,
    String checkFailed,
    String expectedValue) {

  public static ManifestMatchResult success(MetadataCsvRecord record, MediaMetadata metadata) {
    return new ManifestMatchResult(record, metadata, "SUCCESS", null, null);
  }

  public static ManifestMatchResult mismatch(
      MetadataCsvRecord record, MediaMetadata metadata, String checkFailed, String expectedValue) {
    return new ManifestMatchResult(record, metadata, "MISMATCH", checkFailed, expectedValue);
  }

  public boolean isSuccess() {
    return "SUCCESS".equals(validationStatus);
  }
}
