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

  @Value("${mmiv.auth.cookie-secure:false}")
  private boolean secureCookie;

  private final mmiv.transfer.service.FileMutationService mutations;
  private final FolderAccessGuard guard;

  public PortalController(
      MetadataClient metadata,
      PortalClient portal,
      S3Client s3,
      @Value("${mmiv.s3.bucket}") String bucket,
      mmiv.transfer.service.FileMutationService mutations,
      FolderAccessGuard guard) {
    this.metadata = metadata;
    this.portal = portal;
    this.s3 = s3;
    this.bucket = bucket;
    this.mutations = mutations;
    this.guard = guard;
  }

  @ModelAttribute
  void noCache(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }

  @GetMapping("/me")
  public Object me() {
    return metadata.profile();
  }

  @GetMapping("/admin/users")
  public Object users(@RequestParam(defaultValue = "") String q) {
    return portal.access(
        HttpMethod.GET, "/api/admin/user-options?q=" + PortalClient.encode(q), null);
  }

  @GetMapping("/admin/folders")
  public Object folders() {
    return portal.access(HttpMethod.GET, "/api/admin/folders", null);
  }

  @PostMapping("/admin/folders")
  public Object create(@RequestBody Folder request) {
    portal.admin();
    String name = request.name();
    if (name == null
        || !name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}")
        || name.endsWith(" ")
        || name.endsWith(".")
        || name.equals(bucket)
        || name.equals("migrations")
        || name.equals("upload")
        || mmiv.transfer.service.FileMutationService.reserved(name))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid folder name");
    try (var scope = guard.write(name)) {
      try {
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(name + "/")
                .ifNoneMatch("*")
                .contentType("application/x-directory")
                .build(),
            software.amazon.awssdk.core.sync.RequestBody.empty());
      } catch (S3Exception e) {
        if (e.statusCode() != 412)
          throw new ResponseStatusException(
              HttpStatus.BAD_GATEWAY, "Folder could not be created in storage");
      }
      return portal.access(HttpMethod.POST, "/api/admin/folders", request);
    }
  }

  @PutMapping("/admin/users/{id}/permissions")
  public Object grant(@PathVariable UUID id, @RequestBody Grant request) {
    return portal.access(HttpMethod.PUT, "/api/admin/users/" + id + "/destinations", request);
  }

  @DeleteMapping("/admin/users/{id}/permissions")
  public Object revoke(@PathVariable UUID id, @RequestParam String destination) {
    return portal.access(
        HttpMethod.DELETE,
        "/api/admin/users/" + id + "/destinations?destination=" + PortalClient.encode(destination),
        null);
  }

  @PostMapping("/admin/users/{id}/password-reset")
  public Object reset(@PathVariable UUID id) {
    return portal.access(HttpMethod.POST, "/api/admin/users/" + id + "/password-reset", null);
  }

  @DeleteMapping("/admin/users/{id}")
  public void deleteUser(@PathVariable UUID id, jakarta.servlet.http.HttpServletResponse response) {
    boolean self = id.toString().equals(MetadataClient.currentJwt().getSubject());
    portal.access(HttpMethod.DELETE, "/api/admin/users/" + id, null);
    if (self)
      response.addHeader(
          HttpHeaders.SET_COOKIE,
          ResponseCookie.from(mmiv.transfer.config.TransferSecurityConfig.AUTH_COOKIE, "")
              .httpOnly(true)
              .secure(secureCookie)
              .sameSite("Strict")
              .path("/")
              .maxAge(0)
              .build()
              .toString());
    response.setStatus(204);
  }

  @PostMapping("/admin/invitations")
  public Object invite(@RequestBody Invite request) {
    return portal.access(HttpMethod.POST, "/api/admin/invitations", request);
  }

  @PostMapping("/invitations/accept")
  public Object accept(@RequestBody Acceptance request) {
    return portal.access(HttpMethod.POST, "/api/admin/invitations/accept", request);
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

  @GetMapping("/logs")
  public Object logs(
      @RequestParam(required = false) String folder, @RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 100000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    return portal.dashboard(
        "/api/dashboard?page="
            + page
            + (folder == null ? "" : "&folder=" + PortalClient.encode(folder)));
  }

  @GetMapping("/history")
  public Object history(@RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 100000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
    return portal.dashboard("/api/history?page=" + page);
  }

  public record Folder(String name) {}

  public record Grant(String destination, String permission) {}

  public record Invite(String username) {}

  public record Acceptance(String token) {}

  public record FileEntry(
      String fileId, String name, long sizeBytes, String modifiedAt, boolean canDelete) {}
}
