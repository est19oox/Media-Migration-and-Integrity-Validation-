package mmiv.controller;

import java.util.UUID;
import mmiv.integration.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {
  private final MetadataService metadata;
  private final TransferClient transfer;
  private final VerificationCleanupService cleanup;

  public MetadataController(
      MetadataService metadata, TransferClient transfer, VerificationCleanupService cleanup) {
    this.metadata = metadata;
    this.transfer = transfer;
    this.cleanup = cleanup;
  }

  @PostMapping("/plans")
  public MetadataPlan create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody MetadataService.CreatePlan request,
      @RequestHeader(value = "X-MMIV-Verification-Run", required = false) UUID run) {
    return metadata.create(jwt, request, false, run);
  }

  @PostMapping("/allocations")
  public MetadataPlan allocate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody MetadataService.CreatePlan request,
      @RequestHeader(value = "X-MMIV-Verification-Run", required = false) UUID run) {
    return metadata.create(jwt, request, true, run);
  }

  @GetMapping("/verification/{run}/closed")
  public Object closed(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID run) {
    return java.util.Map.of("closed", cleanup.closed(jwt, run));
  }

  @GetMapping("/verification/{run}/plans")
  public java.util.List<MetadataPlan> verificationPlans(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID run) {
    return cleanup.plans(jwt, run);
  }

  @PostMapping("/verification/{run}/cleanup")
  public Object cleanup(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID run) {
    return cleanup.clean(jwt, run);
  }

  @GetMapping("/folders/{folder}/plans")
  public Object folderPlans(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String folder,
      @RequestParam(defaultValue = "0") int page) {
    return metadata.folderPlans(jwt, folder, page);
  }

  @GetMapping("/plans/{id}/delete-authorization")
  public MetadataPlan deleteAuthorization(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return metadata.deleteAuthorization(jwt, id);
  }

  @DeleteMapping("/plans/{id}")
  public Object delete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam String key) {
    return java.util.Map.of("deleted", metadata.deletePlan(jwt, id, key));
  }

  @DeleteMapping("/folders/{folder}/plans")
  public Object deleteFolder(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String folder,
      @RequestParam(required = false) String path) {
    return java.util.Map.of("deleted", metadata.deleteFolderPlans(jwt, folder, path));
  }

  @PostMapping("/plans/{id}/release-name")
  public void releaseName(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    metadata.releaseName(jwt, id);
  }

  @PostMapping("/ownership")
  public Object owned(@AuthenticationPrincipal Jwt jwt, @RequestBody java.util.List<UUID> ids) {
    return metadata.ownedKeys(jwt, ids);
  }

  @GetMapping("/plans/{id}")
  public MetadataPlan read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return metadata.get(jwt, id, "READ");
  }

  @GetMapping("/plans/{id}/resolve")
  public MetadataPlan resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return metadata.get(jwt, id, "WRITE");
  }

  @PostMapping("/plans/{id}/transfer")
  public Object transfer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    metadata.get(jwt, id, "WRITE");
    return transfer.transfer(id, jwt.getTokenValue());
  }
}
