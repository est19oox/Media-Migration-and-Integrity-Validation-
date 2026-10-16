package mmiv.integration;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import mmiv.events.EventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MetadataService {
  private final PermissionClient permissions;
  private final MetadataPlanRepository plans;
  private final EventPublisher events;
  private final Path root;
  private final String destination;
  private final String bucket;

  public MetadataService(
      PermissionClient permissions,
      MetadataPlanRepository plans,
      EventPublisher events,
      @Value("${discovery.root}") String root,
      @Value("${mmiv.metadata.destination-id:${mmiv.s3.bucket:media-migration-dev-landing}}")
          String destination,
      @Value("${mmiv.s3.bucket:media-migration-dev-landing}") String bucket) {
    this.permissions = permissions;
    this.plans = plans;
    this.events = events;
    this.root = Path.of(root);
    this.destination = destination;
    this.bucket = bucket;
  }

  public MetadataPlan create(Jwt jwt, CreatePlan request, boolean upload) {
    return create(jwt, request, upload, null);
  }

  public MetadataPlan create(Jwt jwt, CreatePlan request, boolean upload, UUID verificationRun) {
    if (request == null
        || request.destinationId() == null
        || !request.destinationId().matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}")
        || bucket.equals(request.destinationId()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a registered folder");
    permissions.requireFolder(jwt.getTokenValue(), request.destinationId());
    if (request.destinationId().startsWith("mmiv-verification-")
        && (verificationRun == null
            || !request.destinationId().equals("mmiv-verification-" + verificationRun)))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Verification folder requires its run ID");
    permissions.require(jwt.getTokenValue(), jwt.getSubject(), request.destinationId(), "WRITE");
    String name;
    String source = null;
    long size;
    if (upload) {
      name = request.fileName();
      size = request.sizeBytes() == null ? 0 : request.sizeBytes();
    } else {
      try {
        if (request.sourcePath() == null || Path.of(request.sourcePath()).isAbsolute())
          throw new IOException();
        var base = root.toRealPath();
        var path = base.resolve(request.sourcePath()).toRealPath();
        if (!path.startsWith(base) || !Files.isRegularFile(path) || !Files.isReadable(path))
          throw new IOException();
        source = base.relativize(path).toString().replace('\\', '/');
        name = path.getFileName().toString();
        size = Files.size(path);
      } catch (IOException | InvalidPathException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media source");
      }
    }
    if (name == null
        || !name.matches("[\\p{L}\\p{N}._ ()-]{1,200}")
        || name.equals(".")
        || name.equals("..")
        || size <= 0
        || size > 104857600)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media file");
    String contentType = contentType(name);
    String objectPath = upload && request.relativePath() != null ? request.relativePath() : name;
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
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid relative media path");
    var id = UUID.randomUUID();
    var file = UUID.randomUUID();
    if ((request.destinationId() + "/migrations/" + id + "/" + objectPath)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            .length
        > 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File path is too long");
    var plan =
        new MetadataPlan(
            id,
            id,
            file,
            jwt.getSubject(),
            request.destinationId(),
            bucket,
            request.destinationId() + "/migrations/" + id + "/" + objectPath,
            source,
            name,
            contentType,
            size,
            upload ? "UPLOAD" : "SOURCE",
            objectPath,
            verificationRun);
    if (request.occupiedPaths() != null
        && (request.occupiedPaths().size() > 100000
            || request.occupiedPaths().stream().anyMatch(p -> p == null || p.length() > 900)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid folder inventory");
    var named = plans.saveNamed(plan, request.occupiedPaths());
    if (named != null) plan = named;
    events.migration(
        "MIGRATION_STARTED",
        "migration.started",
        id,
        null,
        Map.of(
            "initiatedBy",
            jwt.getSubject(),
            "source",
            Map.of("type", "DIRECT_UPLOAD", "rootId", "media")));
    events.migration(
        "FILE_DISCOVERY_COMPLETED",
        "discovery.file.completed",
        id,
        file,
        Map.of(
            "status",
            "ACCEPTED",
            "fileName",
            name,
            "relativePath",
            source == null ? objectPath : source,
            "mediaType",
            contentType.startsWith("image/")
                ? "IMAGE"
                : contentType.startsWith("video/")
                    ? "VIDEO"
                    : contentType.startsWith("audio/") ? "AUDIO" : "DOCUMENT",
            "format",
            name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT),
            "sizeBytes",
            size));
    events.migration(
        "MIGRATION_DISCOVERY_COMPLETED",
        "discovery.migration.completed",
        id,
        null,
        Map.of("status", "COMPLETED", "totalFiles", 1, "acceptedFiles", 1, "rejectedFiles", 0));
    return plan;
  }

  public BatchAllocationResponse allocateBatch(
      Jwt jwt, BatchAllocationRequest request, UUID verificationRun) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid allocation batch");
    }
    String destinationId = request.destinationId();
    if (destinationId == null
        || !destinationId.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}")
        || bucket.equals(destinationId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a registered folder");
    }
    permissions.requireFolder(jwt.getTokenValue(), destinationId);
    if (destinationId.startsWith("mmiv-verification-")
        && (verificationRun == null
            || !destinationId.equals("mmiv-verification-" + verificationRun))) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Verification folder requires its run ID");
    }
    permissions.require(jwt.getTokenValue(), jwt.getSubject(), destinationId, "WRITE");

    List<BatchAllocationRequest.FileItem> files = request.files();
    if (files == null || files.isEmpty() || files.size() > 500) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "A batch must contain between 1 and 500 files");
    }
    validateOccupied(request.occupiedPaths());

    UUID migrationId = UUID.randomUUID();
    var pending = new ArrayList<MetadataPlan>(files.size());
    for (BatchAllocationRequest.FileItem file : files) {
      pending.add(uploadPlan(jwt, destinationId, file, migrationId, verificationRun));
    }

    List<MetadataPlan> named = plans.saveNamedBatch(pending, request.occupiedPaths());
    publishBatchEvents(jwt.getSubject(), migrationId, named);
    return new BatchAllocationResponse(migrationId, named);
  }

  private MetadataPlan uploadPlan(
      Jwt jwt,
      String destinationId,
      BatchAllocationRequest.FileItem file,
      UUID migrationId,
      UUID verificationRun) {
    if (file == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media file");
    }
    String name = file.fileName();
    long size = file.sizeBytes() == null ? 0 : file.sizeBytes();
    if (name == null
        || !name.matches("[\\p{L}\\p{N}._ ()-]{1,200}")
        || name.equals(".")
        || name.equals("..")
        || size <= 0
        || size > 104857600) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media file");
    }
    String contentType = contentType(name);
    String objectPath = file.relativePath() == null ? name : file.relativePath();
    if (objectPath.startsWith("/")
        || objectPath.contains("\\")
        || !objectPath.endsWith(name)
        || !objectPath.substring(objectPath.lastIndexOf('/') + 1).equals(name)
        || objectPath.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 900
        || Arrays.stream(objectPath.split("/", -1))
            .anyMatch(
                part ->
                    part.equals(".")
                        || part.equals("..")
                        || part.isBlank()
                        || !part.matches("[\\p{L}\\p{N}._ ()-]{1,200}"))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid relative media path");
    }
    UUID metadataId = UUID.randomUUID();
    UUID fileId = UUID.randomUUID();
    String key = destinationId + "/migrations/" + metadataId + "/" + objectPath;
    if (key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File path is too long");
    }
    return new MetadataPlan(
        metadataId,
        migrationId,
        fileId,
        jwt.getSubject(),
        destinationId,
        bucket,
        key,
        null,
        name,
        contentType,
        size,
        "UPLOAD",
        objectPath,
        verificationRun);
  }

  private void validateOccupied(Collection<String> occupiedPaths) {
    if (occupiedPaths != null
        && (occupiedPaths.size() > 100000
            || occupiedPaths.stream().anyMatch(path -> path == null || path.length() > 900))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid folder inventory");
    }
  }

  private void publishBatchEvents(String subject, UUID migrationId, List<MetadataPlan> namedPlans) {
    events.migration(
        "MIGRATION_STARTED",
        "migration.started",
        migrationId,
        null,
        Map.of(
            "initiatedBy", subject, "source", Map.of("type", "DIRECT_UPLOAD", "rootId", "media")));
    for (MetadataPlan plan : namedPlans) {
      events.migration(
          "FILE_DISCOVERY_COMPLETED",
          "discovery.file.completed",
          migrationId,
          plan.fileId(),
          Map.of(
              "status",
              "ACCEPTED",
              "fileName",
              plan.fileName(),
              "relativePath",
              plan.objectPath(),
              "mediaType",
              mediaType(plan.contentType()),
              "format",
              plan.fileName()
                  .substring(plan.fileName().lastIndexOf('.') + 1)
                  .toUpperCase(Locale.ROOT),
              "sizeBytes",
              plan.sizeBytes()));
    }
    events.migration(
        "MIGRATION_DISCOVERY_COMPLETED",
        "discovery.migration.completed",
        migrationId,
        null,
        Map.of(
            "status",
            "COMPLETED",
            "totalFiles",
            namedPlans.size(),
            "acceptedFiles",
            namedPlans.size(),
            "rejectedFiles",
            0));
  }

  private static String mediaType(String contentType) {
    return contentType.startsWith("image/")
        ? "IMAGE"
        : contentType.startsWith("video/")
            ? "VIDEO"
            : contentType.startsWith("audio/") ? "AUDIO" : "DOCUMENT";
  }

  public MetadataPlan get(Jwt jwt, UUID id, String action) {
    var found = "READ".equals(action) ? plans.findAny(id) : plans.find(id, jwt.getSubject());
    var plan =
        found.orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Metadata not found"));
    if ("WRITE".equals(action))
      permissions.requireFolder(jwt.getTokenValue(), plan.destinationId());
    if ("READ".equals(action)
        && !plan.destinationKey().startsWith(plan.destinationId() + "/")
        && !plan.ownerId().equals(jwt.getSubject()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Metadata not found");
    permissions.require(jwt.getTokenValue(), jwt.getSubject(), plan.destinationId(), action);
    if ("WRITE".equals(action)
        && plan.verificationRunId() != null
        && plans.isClosed(plan.ownerId(), plan.verificationRunId()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification run is closed");
    return plan;
  }

  public String destination() {
    return destination;
  }

  public MetadataPlan deleteAuthorization(Jwt jwt, UUID id) {
    var plan =
        plans
            .find(id, jwt.getSubject(), false)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Only the uploader can delete this file"));
    permissions.requireFolder(jwt.getTokenValue(), plan.destinationId());
    permissions.require(jwt.getTokenValue(), jwt.getSubject(), plan.destinationId(), "WRITE");
    return plan;
  }

  public void releaseName(Jwt jwt, UUID id) {
    var plan =
        plans
            .find(id, jwt.getSubject(), false)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.FORBIDDEN, "Assignment owner required"));
    permissions.require(jwt.getTokenValue(), jwt.getSubject(), plan.destinationId(), "WRITE");
    plans.releaseName(id, jwt.getSubject());
  }

  public int deletePlan(Jwt jwt, UUID id, String key) {
    var plan = plans.findAny(id).orElse(null);
    if (plan == null) return 0;
    if (!plan.destinationKey().equals(key))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "File assignment mismatch");
    if (!plan.ownerId().equals(jwt.getSubject())) permissions.requireAdmin(jwt.getTokenValue());
    permissions.require(jwt.getTokenValue(), jwt.getSubject(), plan.destinationId(), "WRITE");
    return plans.delete(id);
  }

  public int deleteFolderPlans(Jwt jwt, String folder, String path) {
    permissions.requireAdmin(jwt.getTokenValue());
    permissions.requireFolder(jwt.getTokenValue(), folder);
    if (path != null
        && (path.isBlank()
            || path.length() > 900
            || Arrays.stream(path.split("/", -1))
                .anyMatch(
                    p ->
                        p.equals(".")
                            || p.equals("..")
                            || !p.matches("[\\p{L}\\p{N}._ ()-]{1,200}"))))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid folder path");
    return plans.deleteFolder(folder, path);
  }

  public List<String> ownedKeys(Jwt jwt, List<UUID> ids) {
    permissions.requireActive(jwt.getTokenValue());
    if (ids == null || ids.size() > 100)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many IDs");
    return plans.ownedKeys(jwt.getSubject(), ids);
  }

  public List<MetadataPlan> folderPlans(Jwt jwt, String folder, int page) {
    if (page < 0 || page > 100000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    permissions.requireFolder(jwt.getTokenValue(), folder);
    permissions.require(jwt.getTokenValue(), jwt.getSubject(), folder, "READ");
    return plans.folderPlans(folder, page);
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
      default ->
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported media type");
    };
  }

  public record CreatePlan(
      String destinationId,
      String sourcePath,
      String fileName,
      Long sizeBytes,
      String relativePath,
      java.util.List<String> occupiedPaths) {
    public CreatePlan(
        String destinationId,
        String sourcePath,
        String fileName,
        Long sizeBytes,
        String relativePath) {
      this(destinationId, sourcePath, fileName, sizeBytes, relativePath, null);
    }

    public CreatePlan(String destinationId, String sourcePath, String fileName, long sizeBytes) {
      this(destinationId, sourcePath, fileName, sizeBytes, null, null);
    }
  }
}
