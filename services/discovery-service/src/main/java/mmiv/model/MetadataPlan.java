package mmiv.model;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public record MetadataPlan(
    UUID metadataId,
    UUID migrationId,
    UUID fileId,
    String ownerId,
    String destinationId,
    String bucket,
    String destinationKey,
    String sourcePath,
    String fileName,
    String contentType,
    long sizeBytes,
    String kind,
    String objectPath,
    UUID verificationRunId) {
  public MetadataPlan(
      UUID metadataId,
      UUID migrationId,
      UUID fileId,
      String ownerId,
      String destinationId,
      String bucket,
      String destinationKey,
      String sourcePath,
      String fileName,
      String contentType,
      long sizeBytes,
      String kind,
      String objectPath) {
    this(
        metadataId,
        migrationId,
        fileId,
        ownerId,
        destinationId,
        bucket,
        destinationKey,
        sourcePath,
        fileName,
        contentType,
        sizeBytes,
        kind,
        objectPath,
        null);
  }

  public static boolean validDestination(String destination, String bucket) {
    return destination != null
        && destination.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}")
        && !bucket.equals(destination);
  }

  public static boolean acceptsVerificationRun(String destination, UUID run) {
    return !destination.startsWith("mmiv-verification-")
        || run != null && destination.equals("mmiv-verification-" + run);
  }

  /**
   * Creates a valid destination assignment; filesystem and permissions are application concerns.
   */
  public static MetadataPlan assign(
      String ownerId,
      String destinationId,
      String bucket,
      String source,
      String name,
      long size,
      boolean upload,
      String relativePath,
      UUID verificationRun) {
    UUID metadataId = UUID.randomUUID();
    return assign(
        ownerId,
        destinationId,
        bucket,
        source,
        name,
        size,
        upload,
        relativePath,
        verificationRun,
        metadataId,
        metadataId);
  }

  public static MetadataPlan assign(
      String ownerId,
      String destinationId,
      String bucket,
      String source,
      String name,
      long size,
      boolean upload,
      String relativePath,
      UUID verificationRun,
      UUID migrationId) {
    return assign(
        ownerId,
        destinationId,
        bucket,
        source,
        name,
        size,
        upload,
        relativePath,
        verificationRun,
        UUID.randomUUID(),
        migrationId);
  }

  private static MetadataPlan assign(
      String ownerId,
      String destinationId,
      String bucket,
      String source,
      String name,
      long size,
      boolean upload,
      String relativePath,
      UUID verificationRun,
      UUID metadataId,
      UUID migrationId) {
    if (name == null
        || !name.matches("[\\p{L}\\p{N}._ ()-]{1,200}")
        || name.equals(".")
        || name.equals("..")
        || size <= 0
        || size > 104857600) throw new IllegalArgumentException("Invalid media file");
    String contentType = contentType(name);
    String objectPath = upload && relativePath != null ? relativePath : name;
    if (objectPath.startsWith("/")
        || objectPath.contains("\\")
        || !objectPath.endsWith(name)
        || !objectPath.substring(objectPath.lastIndexOf('/') + 1).equals(name)
        || objectPath.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 900
        || java.util.Arrays.stream(objectPath.split("/", -1))
            .anyMatch(
                part ->
                    part.equals(".")
                        || part.equals("..")
                        || part.isBlank()
                        || !part.matches("[\\p{L}\\p{N}._ ()-]{1,200}")))
      throw new IllegalArgumentException("Invalid relative media path");
    var file = UUID.randomUUID();
    if ((destinationId + "/migrations/" + metadataId + "/" + objectPath)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            .length
        > 1024) throw new IllegalArgumentException("File path is too long");
    return new MetadataPlan(
        metadataId,
        migrationId,
        file,
        ownerId,
        destinationId,
        bucket,
        destinationId + "/migrations/" + metadataId + "/" + objectPath,
        source,
        name,
        contentType,
        size,
        upload ? "UPLOAD" : "SOURCE",
        objectPath,
        verificationRun);
  }

  /** Called with the folder namespace locked by the persistence adapter. */
  public MetadataPlan reserveAvailableName(Set<String> occupied) {
    String path = availableName(objectPath, occupied);
    String name = path.substring(path.lastIndexOf('/') + 1);
    String key = destinationId + "/migrations/" + metadataId + "/" + path;
    if (name.length() > 200
        || path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 900
        || key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024)
      throw new IllegalArgumentException(
          "File path is too long after adding a duplicate-name suffix");
    return new MetadataPlan(
        metadataId,
        migrationId,
        fileId,
        ownerId,
        destinationId,
        bucket,
        key,
        sourcePath,
        name,
        contentType,
        sizeBytes,
        kind,
        path,
        verificationRunId);
  }

  public boolean belongsTo(String userId) {
    return ownerId.equals(userId);
  }

  public boolean visibleTo(String userId) {
    return destinationKey.startsWith(destinationId + "/") || belongsTo(userId);
  }

  public boolean requiresOpenVerificationRunForWrite() {
    return verificationRunId != null;
  }

  public String mediaType() {
    if (contentType.startsWith("image/")) return "IMAGE";
    if (contentType.startsWith("video/")) return "VIDEO";
    if (contentType.startsWith("audio/")) return "AUDIO";
    return "DOCUMENT";
  }

  public static boolean validOccupiedPaths(Collection<String> occupied) {
    return occupied == null
        || occupied.size() <= 100000
            && occupied.stream().allMatch(path -> path != null && path.length() <= 900);
  }

  public static boolean validFolderPath(String path) {
    return path == null
        || !path.isBlank()
            && path.length() <= 900
            && java.util.Arrays.stream(path.split("/", -1))
                .allMatch(
                    part ->
                        part.matches("[\\p{L}\\p{N}._ ()-]{1,200}")
                            && !part.equals(".")
                            && !part.equals(".."));
  }

  public boolean matchesObject(String key) {
    return destinationKey.equals(key);
  }

  private static String contentType(String name) {
    String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    return switch (extension) {
      case "mp4" -> "video/mp4";
      case "mp3" -> "audio/mpeg";
      case "png" -> "image/png";
      case "txt" -> "text/plain";
      case "jpg", "jpeg" -> "image/jpeg";
      case "doc" -> "application/msword";
      case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      case "pdf" -> "application/pdf";
      case "ppt" -> "application/vnd.ms-powerpoint";
      case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
      default -> throw new IllegalArgumentException("Unsupported media type");
    };
  }

  public static String availableName(String path, Set<String> used) {
    if (!used.contains(path)) return path;
    int slash = path.lastIndexOf('/');
    String directory = slash < 0 ? "" : path.substring(0, slash + 1),
        file = path.substring(slash + 1);
    int dot = file.lastIndexOf('.');
    String base = dot > 0 ? file.substring(0, dot) : file,
        extension = dot > 0 ? file.substring(dot) : "";
    for (int n = 2; n < Integer.MAX_VALUE; n++) {
      String candidate = directory + base + " (" + n + ")" + extension;
      if (!used.contains(candidate)) return candidate;
    }
    throw new IllegalArgumentException("No available file name");
  }
}
