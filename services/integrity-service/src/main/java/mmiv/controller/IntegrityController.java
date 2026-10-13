package mmiv.controller;

import java.io.IOException;
import mmiv.dto.ChecksumResponse;
import mmiv.dto.ValidationRequest;
import mmiv.dto.ValidationResponse;
import mmiv.service.ChecksumService;
import mmiv.service.IntegrityValidationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/integrity")
public class IntegrityController {

  private final mmiv.integration.IntegrityMetadataGuard metadata;
  private final mmiv.events.EventPublisher events;
  private final ChecksumService checksumService;
  private final IntegrityValidationService integrityValidationService;

  public IntegrityController(
      ChecksumService checksumService,
      IntegrityValidationService integrityValidationService,
      mmiv.integration.IntegrityMetadataGuard metadata,
      mmiv.events.EventPublisher events) {
    this.metadata = metadata;
    this.events = events;

    this.checksumService = checksumService;
    this.integrityValidationService = integrityValidationService;
  }

  @PostMapping("/checksum")
  public ResponseEntity<ChecksumResponse> calculateChecksum(
      @RequestParam("file") MultipartFile file) throws IOException {

    String checksum = checksumService.calculateSHA256(file);

    ChecksumResponse response =
        new ChecksumResponse(file.getOriginalFilename(), file.getSize(), "SHA-256", checksum);

    return ResponseEntity.ok(response);
  }

  @PostMapping("/validate")
  public ResponseEntity<ValidationResponse> validate(
      @RequestBody ValidationRequest request,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          org.springframework.security.oauth2.jwt.Jwt jwt,
      @RequestHeader(value = "X-MMIV-Metadata-Id", required = false) java.util.UUID metadataId,
      @RequestHeader(value = "X-MMIV-Migration-Id", required = false) java.util.UUID migrationId,
      @RequestHeader(value = "X-MMIV-File-Id", required = false) java.util.UUID fileId)
      throws IOException {

    if (request.bucket() == null
        || request.bucket().isBlank()
        || request.objectKey() == null
        || request.objectKey().isBlank()
        || request.sourceChecksum() == null
        || !request.sourceChecksum().matches("(?i)[0-9a-f]{64}")
        || (request.transferId() != null
            && (request.transferId().isBlank() || request.transferId().length() > 128))) {
      return ResponseEntity.badRequest().build();
    }
    try {
      java.util.UUID.fromString(request.transferId());
    } catch (IllegalArgumentException | NullPointerException invalid) {
      return ResponseEntity.badRequest().build();
    }
    metadata.require(jwt, metadataId, migrationId, fileId, request);
    long started = System.nanoTime();
    try {
      ValidationResponse response =
          integrityValidationService.validate(
              request.bucket(), request.objectKey(), request.sourceChecksum());
      publish(
          migrationId,
          fileId,
          request.transferId(),
          response.valid() ? "PASSED" : "FAILED",
          started);
      return ResponseEntity.ok(
          new ValidationResponse(
              response.bucket(),
              response.objectKey(),
              response.algorithm(),
              response.sourceChecksum(),
              response.destinationChecksum(),
              response.valid(),
              request.transferId(),
              response.valid() ? "PASSED" : "FAILED"));
    } catch (IOException | RuntimeException e) {
      publish(migrationId, fileId, request.transferId(), "ERROR", started);
      throw e;
    }
  }

  private void publish(
      java.util.UUID migrationId,
      java.util.UUID fileId,
      String transferId,
      String status,
      long started) {
    if (migrationId == null || fileId == null) return;
    var data = new java.util.LinkedHashMap<String, Object>();
    data.put("transferId", transferId);
    data.put("status", status);
    data.put("checksumAlgorithm", "SHA-256");
    data.put("durationMs", (System.nanoTime() - started) / 1000000);
    if (!"PASSED".equals(status))
      data.put(
          "failure",
          java.util.Map.of(
              "code",
              "FAILED".equals(status) ? "CHECKSUM_MISMATCH" : "VALIDATION_ERROR",
              "message",
              "Integrity validation did not pass",
              "retryable",
              "ERROR".equals(status)));
    events.migration(
        "INTEGRITY_VALIDATION_COMPLETED",
        "integrity.validation.completed",
        migrationId,
        fileId,
        data);
  }
}
