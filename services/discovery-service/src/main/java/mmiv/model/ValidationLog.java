package mmiv.model;

import java.time.LocalDateTime;

/** Represents one validation log entry stored in the Oracle MEDIA_LOG table. */
public record ValidationLog(
    String fileName,
    String fileType,
    String fileDir,
    Long fileSize,
    String validationStatus,
    String checkFailed,
    String expectedValue,
    LocalDateTime checkedAt) {

  /** Convert a manifest matching result into a database validation log. */
  public static ValidationLog from(ManifestMatchResult result) {

    MediaMetadata actual = result.actualMetadata();

    String fileName = actual != null ? actual.getFileName() : result.manifestRecord().fileName();

    String fileType = actual != null ? actual.getMediaType() : null;

    String fileDir = actual != null ? actual.getSrcDir() : null;

    Long fileSize = actual != null ? actual.getFileSize() : null;

    return new ValidationLog(
        fileName,
        fileType,
        fileDir,
        fileSize,
        result.validationStatus(),
        result.checkFailed(),
        result.expectedValue(),
        LocalDateTime.now());
  }
}
