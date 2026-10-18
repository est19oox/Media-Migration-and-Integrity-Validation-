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
    return new mmiv.transfer.domain.StoredFile(key).migrationId();
  }

  public Object deleteFile(String id) {
    String key = FileIds.decode(id);
    int slash = key.indexOf('/');
    if (slash < 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to folder");
    String folder = key.substring(0, slash);
    Instant started = Instant.now();
    try (@SuppressWarnings("unused")
        var scope = folders.write(folder)) {
      metadata.requireFolder(folder, "WRITE");
      boolean admin = "ADMIN".equals(metadata.profile().role());
      UUID migration = migration(key);
      var storedFile = new mmiv.transfer.domain.StoredFile(key);
      String authorizedKey =
          !admin && migration != null ? metadata.resolveDelete(migration).destinationKey() : null;
      if (!storedFile.canDelete(admin, authorizedKey))
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Only the uploader can delete this file");
      int count = storage.delete(bucket, key, key::equals);
      if (migration != null) metadata.deletePlan(migration, key);
      logs.deletion("DELETE", key, started);
      return Map.of("deletedVersions", count);
    }
  }

  public Object deleteFolder(String folder, String path) {
    mmiv.transfer.domain.StoredFolder selected;
    try {
      selected = new mmiv.transfer.domain.StoredFolder(folder, path);
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
    }
    try (var scope = folders.write(folder)) {
      portal.admin();
      metadata.requireFolder(folder, "WRITE");
      String prefix = selected.prefix();
      int count = storage.delete(bucket, prefix, selected::contains);
      metadata.deleteFolderPlans(folder, path);
      if (selected.isRoot())
        portal.access(HttpMethod.DELETE, "/api/admin/folders/" + PortalClient.encode(folder), null);
      logs.deletion("FOLDER_DELETE", prefix + (path == null ? "" : path), Instant.now());
      return Map.of("deletedVersions", count);
    }
  }

  public static String relative(String key, String folder) {
    return mmiv.transfer.domain.StoredFolder.relative(key, folder);
  }

  public static boolean reserved(String folder) {
    return mmiv.transfer.domain.StoredFolder.reserved(folder);
  }
}
