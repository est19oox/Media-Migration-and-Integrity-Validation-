package mmiv.integration;

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
}
