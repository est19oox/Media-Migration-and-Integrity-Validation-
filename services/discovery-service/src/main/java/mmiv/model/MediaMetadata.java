package mmiv.model;

import java.time.LocalDateTime;

public class MediaMetadata {

  private String fileName;
  private String mediaType;
  private long fileSize;
  private String srcDir;
  private LocalDateTime createdDate;

  public MediaMetadata(
      String fileName, String mediaType, long fileSize, String srcDir, LocalDateTime createdDate) {

    this.fileName = fileName;
    this.mediaType = mediaType;
    this.fileSize = fileSize;
    this.srcDir = srcDir;
    this.createdDate = createdDate;
  }

  public String getFileName() {
    return fileName;
  }

  public String getMediaType() {
    return mediaType;
  }

  public long getFileSize() {
    return fileSize;
  }

  public String getSrcDir() {
    return srcDir;
  }

  public LocalDateTime getCreatedDate() {
    return createdDate;
  }

  @Override
  public String toString() {
    return "MediaMetadata{"
        + "fileName='"
        + fileName
        + '\''
        + ", mediaType='"
        + mediaType
        + '\''
        + ", fileSize="
        + fileSize
        + ", srcDir='"
        + srcDir
        + '\''
        + ", createdDate="
        + createdDate
        + '}';
  }

  public static String extension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot == -1 || dot == fileName.length() - 1
        ? "UNKNOWN"
        : fileName.substring(dot + 1).toUpperCase();
  }

  public static boolean supported(String fileName, java.util.Collection<String> formats) {
    return formats.stream().anyMatch(type -> type.equalsIgnoreCase(extension(fileName)));
  }
}
