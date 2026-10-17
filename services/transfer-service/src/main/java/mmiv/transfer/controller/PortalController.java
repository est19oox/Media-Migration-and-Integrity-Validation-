package mmiv.transfer.controller;

import java.util.*;
import mmiv.transfer.files.FileIds;
import mmiv.transfer.integration.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {
  private final MetadataClient metadata;
  private final PortalClient portal;
  private final S3Client s3;
  private final String bucket;

  private final mmiv.transfer.service.FileMutationService mutations;
  private final mmiv.transfer.service.FolderCreationService creation;

  public PortalController(
      MetadataClient metadata,
      PortalClient portal,
      S3Client s3,
      @Value("${mmiv.s3.bucket}") String bucket,
      mmiv.transfer.service.FileMutationService mutations,
      mmiv.transfer.service.FolderCreationService creation) {
    this.metadata = metadata;
    this.portal = portal;
    this.s3 = s3;
    this.bucket = bucket;
    this.mutations = mutations;
    this.creation = creation;
  }

  @ModelAttribute
  void noCache(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }

  @GetMapping("/admin/folders")
  public Object folders() {
    return portal.access(HttpMethod.GET, "/api/admin/folders", null);
  }

  @PostMapping("/admin/folders")
  public Object create(@RequestBody Folder request) {
    return creation.create(request.name());
  }

  @GetMapping("/files")
  public Object files(@RequestParam String folder, @RequestParam(required = false) String cursor) {
    metadata.requireFolder(folder, "READ");
    if (cursor != null && cursor.length() > 4096)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
    var page =
        s3.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(folder + "/")
                .continuationToken(cursor)
                .maxKeys(100)
                .build());
    var profile = metadata.profile();
    boolean admin = "ADMIN".equals(profile.role());
    boolean write =
        admin
            || profile.destinations().stream()
                .anyMatch(f -> f.destination().equals(folder) && f.permission().equals("WRITE"));
    var ids =
        page.contents().stream()
            .map(o -> mmiv.transfer.service.FileMutationService.migration(o.key()))
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    var owned = write && !admin ? metadata.ownedKeys(ids) : Set.<String>of();
    var files =
        page.contents().stream()
            .filter(o -> !o.key().endsWith("/"))
            .map(
                o -> {
                  String relative =
                      o.key()
                          .substring(folder.length() + 1)
                          .replaceFirst("^migrations/[0-9a-fA-F-]{36}/", "");
                  return new FileEntry(
                      FileIds.encode(o.key()),
                      relative,
                      o.size(),
                      o.lastModified() == null ? null : o.lastModified().toString(),
                      admin || owned.contains(o.key()));
                })
            .toList();
    var result = new LinkedHashMap<String, Object>();
    result.put("files", files);
    result.put("nextCursor", page.nextContinuationToken());
    return result;
  }

  @DeleteMapping("/files/{id}")
  public Object deleteFile(@PathVariable String id) {
    return mutations.deleteFile(id);
  }

  @DeleteMapping("/folders")
  public Object deleteFolder(
      @RequestParam String folder, @RequestParam(required = false) String path) {
    return mutations.deleteFolder(folder, path);
  }

  public record Folder(String name) {}

  public record FileEntry(
      String fileId, String name, long sizeBytes, String modifiedAt, boolean canDelete) {}
}
