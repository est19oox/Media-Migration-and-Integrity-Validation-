package mmiv.integration;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import mmiv.events.EventPublisher;
import mmiv.model.MetadataPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MetadataService {
  private final PermissionClient permissions;
  private final MetadataPlanRepository plans;
  private final MetadataAssignmentService assignments;
  private final mmiv.repository.VerificationRunRepository runs;
  private final EventPublisher events;
  private final Path root;
  private final String destination;
  private final String bucket;

  public MetadataService(
      PermissionClient permissions,
      MetadataPlanRepository plans,
      MetadataAssignmentService assignments,
      mmiv.repository.VerificationRunRepository runs,
      EventPublisher events,
      @Value("${discovery.root}") String root,
      @Value("${mmiv.metadata.destination-id:${mmiv.s3.bucket:media-migration-dev-landing}}")
          String destination,
      @Value("${mmiv.s3.bucket:media-migration-dev-landing}") String bucket) {
    this.permissions = permissions;
    this.plans = plans;
    this.assignments = assignments;
    this.runs = runs;
    this.events = events;
    this.root = Path.of(root);
    this.destination = destination;
    this.bucket = bucket;
  }

  public MetadataPlan create(Jwt jwt, CreatePlan request, boolean upload) {
    return create(jwt, request, upload, null);
  }

  public MetadataPlan create(Jwt jwt, CreatePlan request, boolean upload, UUID verificationRun) {
    if (request == null || !MetadataPlan.validDestination(request.destinationId(), bucket))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a registered folder");
    permissions.requireFolder(jwt.getTokenValue(), request.destinationId());
    if (!MetadataPlan.acceptsVerificationRun(request.destinationId(), verificationRun))
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
    MetadataPlan plan;
    try {
      plan =
          MetadataPlan.assign(
              jwt.getSubject(),
              request.destinationId(),
              bucket,
              source,
              name,
              size,
              upload,
              request.relativePath(),
              verificationRun);
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
    }
    var id = plan.migrationId();
    var file = plan.fileId();
    String contentType = plan.contentType();
    String objectPath = plan.objectPath();
    if (!MetadataPlan.validOccupiedPaths(request.occupiedPaths()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid folder inventory");
    var named = assignments.allocate(plan, request.occupiedPaths());
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
            plan.mediaType(),
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

  public MetadataPlan get(Jwt jwt, UUID id, String action) {
    var found = "READ".equals(action) ? plans.findAny(id) : plans.find(id, jwt.getSubject());
    var plan =
        found.orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Metadata not found"));
    if ("WRITE".equals(action))
      permissions.requireFolder(jwt.getTokenValue(), plan.destinationId());
    if ("READ".equals(action) && !plan.visibleTo(jwt.getSubject()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Metadata not found");
    permissions.require(jwt.getTokenValue(), jwt.getSubject(), plan.destinationId(), action);
    if ("WRITE".equals(action)
        && plan.requiresOpenVerificationRunForWrite()
        && runs.isClosed(plan.ownerId(), plan.verificationRunId()))
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
    if (!plan.matchesObject(key))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "File assignment mismatch");
    if (!plan.belongsTo(jwt.getSubject())) permissions.requireAdmin(jwt.getTokenValue());
    permissions.require(jwt.getTokenValue(), jwt.getSubject(), plan.destinationId(), "WRITE");
    return plans.delete(id);
  }

  public int deleteFolderPlans(Jwt jwt, String folder, String path) {
    permissions.requireAdmin(jwt.getTokenValue());
    permissions.requireFolder(jwt.getTokenValue(), folder);
    if (!MetadataPlan.validFolderPath(path))
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
