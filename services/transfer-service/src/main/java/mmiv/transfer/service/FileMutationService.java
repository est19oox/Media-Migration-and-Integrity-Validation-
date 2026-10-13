package mmiv.transfer.service;

import java.time.Instant;
import java.util.*;
import mmiv.transfer.files.FileIds;
import mmiv.transfer.integration.*;
import mmiv.transfer.logging.FileEventLogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FileMutationService {
  private final MetadataClient metadata;
  private final PortalClient portal;
  private final FolderAccessGuard folders;
  private final StorageDeletionService storage;
  private final String bucket;
  private final FileEventLogger logs;

  public FileMutationService(
      MetadataClient metadata,
      PortalClient portal,
      FolderAccessGuard folders,
      StorageDeletionService storage,
      @Value("${mmiv.s3.bucket}") String bucket,
      FileEventLogger logs) {
    this.metadata = metadata;
    this.portal = portal;
    this.folders = folders;
    this.storage = storage;
    this.bucket = bucket;
    this.logs = logs;
  }

  public static UUID migration(String key) {
    var m = java.util.regex.Pattern.compile("^[^/]+/migrations/([0-9a-fA-F-]{36})/").matcher(key);
    try {
      return m.find() ? UUID.fromString(m.group(1)) : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public Object deleteFile(String id) {
    String key = FileIds.decode(id);
    int slash = key.indexOf('/');
    if (slash < 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to folder");
    String folder = key.substring(0, slash);
    Instant started = Instant.now();
    try (var scope = folders.write(folder)) {
      metadata.requireFolder(folder, "WRITE");
      boolean admin = "ADMIN".equals(metadata.profile().role());
      UUID migration = migration(key);
      if (!admin) {
        if (migration == null || !key.equals(metadata.resolveDelete(migration).destinationKey()))
          throw new ResponseStatusException(
              HttpStatus.FORBIDDEN, "Only the uploader can delete this file");
      }
      int count = storage.delete(bucket, key, key::equals);
      if (migration != null) metadata.deletePlan(migration, key);
      logs.deletion("DELETE", key, started);
      return Map.of("deletedVersions", count);
    }
  }

  public Object deleteFolder(String folder, String path) {
    if (folder == null || !folder.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}") || reserved(folder))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid folder");
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
    try (var scope = folders.write(folder)) {
      portal.admin();
      metadata.requireFolder(folder, "WRITE");
      String prefix = folder + "/";
      int count =
          storage.delete(
              bucket,
              prefix,
              key ->
                  key.startsWith(prefix)
                      && (path == null || relative(key, folder).startsWith(path + "/")));
      metadata.deleteFolderPlans(folder, path);
      if (path == null)
        portal.access(HttpMethod.DELETE, "/api/admin/folders/" + PortalClient.encode(folder), null);
      logs.deletion("FOLDER_DELETE", prefix + (path == null ? "" : path), Instant.now());
      return Map.of("deletedVersions", count);
    }
  }

  public static String relative(String key, String folder) {
    return key.substring(folder.length() + 1).replaceFirst("^migrations/[0-9a-fA-F-]{36}/", "");
  }

  public static boolean reserved(String folder) {
    return folder.equals("verification") || folder.startsWith("mmiv-verification-");
  }
}
