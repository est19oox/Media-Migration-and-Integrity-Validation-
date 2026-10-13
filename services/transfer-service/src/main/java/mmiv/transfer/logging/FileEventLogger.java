package mmiv.transfer.logging;

import java.time.Instant;
import java.util.UUID;
import mmiv.transfer.dto.TransferResponse;
import mmiv.transfer.dto.UploadResponse;
import mmiv.transfer.files.StoredFile;
import org.springframework.stereotype.Component;

@Component
public class FileEventLogger {
  private final mmiv.events.EventPublisher outbox;

  public FileEventLogger(mmiv.events.EventPublisher outbox) {
    this.outbox = outbox;
  }

  public void upload(UploadResponse result, Instant startedAt) {
    emit(
        "UPLOAD",
        result.transferId(),
        result.fileId(),
        result.fileName(),
        result.bucket(),
        result.key(),
        result.sizeBytes(),
        null,
        result.sha256(),
        result.validationResult(),
        result.cleanupStatus(),
        result.success(),
        null,
        startedAt);
  }

  public void deletion(String operation, String key, Instant startedAt) {
    emit(
        operation,
        null,
        null,
        key.substring(key.lastIndexOf('/') + 1),
        null,
        key,
        null,
        null,
        null,
        null,
        null,
        true,
        null,
        startedAt);
  }

  public void transfer(TransferResponse result, Instant startedAt) {
    String key = result.key();
    emit(
        "TRANSFER",
        result.transferId(),
        result.fileId(),
        key.substring(key.lastIndexOf('/') + 1),
        result.bucket(),
        key,
        result.sizeBytes(),
        null,
        result.sha256(),
        result.validationResult(),
        result.cleanupStatus(),
        result.success(),
        null,
        startedAt);
  }

  public void download(
      String operation,
      String fileId,
      StoredFile file,
      long bytesServed,
      boolean success,
      String errorType,
      Instant startedAt) {
    emit(
        operation,
        null,
        fileId,
        file == null ? null : file.fileName(),
        file == null ? null : file.bucket(),
        file == null ? null : file.key(),
        file == null ? null : file.sizeBytes(),
        bytesServed,
        null,
        null,
        null,
        success,
        errorType,
        startedAt);
  }

  public void failure(
      String operation, String fileName, String key, RuntimeException error, Instant startedAt) {
    emit(
        operation,
        null,
        null,
        fileName,
        null,
        key,
        null,
        null,
        null,
        null,
        null,
        false,
        error.getClass().getSimpleName(),
        startedAt);
  }

  private void emit(
      String operation,
      String transferId,
      String fileId,
      String fileName,
      String bucket,
      String key,
      Long sizeBytes,
      Long bytesServed,
      String sha256,
      String validationResult,
      String cleanupStatus,
      boolean success,
      String errorType,
      Instant startedAt) {
    var data = new java.util.LinkedHashMap<String, Object>();
    data.put("operation", operation);
    data.put("success", success);
    if (org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication()
        instanceof
        org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
            actor) data.put("actorId", actor.getToken().getSubject());
    if (sizeBytes != null) data.put("sizeBytes", sizeBytes);
    if (key != null && key.indexOf('/') > 0) data.put("folder", key.substring(0, key.indexOf('/')));
    if (transferId != null) data.put("transferId", transferId);
    if (fileName != null) data.put("fileName", fileName);
    if (errorType != null) data.put("errorType", errorType);
    if (bytesServed != null) data.put("bytesServed", bytesServed);
    if (validationResult != null) data.put("validationResult", validationResult);
    if (key != null) {
      var match =
          java.util.regex.Pattern.compile("^(?:[^/]+/)?migrations/([0-9a-fA-F-]{36})/")
              .matcher(key);
      try {
        if (match.find()) data.put("migrationId", UUID.fromString(match.group(1)).toString());
      } catch (IllegalArgumentException ignored) {
      }
    }
    try {
      outbox.audit("FILE_OPERATION", data);
      var plan = mmiv.transfer.integration.MigrationContext.get();
      if (plan != null && ("TRANSFER".equals(operation) || "UPLOAD".equals(operation))) {
        String id = transferId == null ? UUID.randomUUID().toString() : transferId;
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("transferId", id);
        payload.put(
            "durationMs",
            Math.max(0, java.time.Duration.between(startedAt, Instant.now()).toMillis()));
        boolean stored = transferId != null;
        payload.put("status", stored ? "STORED" : "FAILED");
        if (!stored)
          payload.put(
              "failure",
              java.util.Map.of(
                  "code", "TRANSFER_ERROR", "message", "Transfer failed", "retryable", true));
        outbox.migration(
            "FILE_TRANSFER_COMPLETED",
            "transfer.file.completed",
            plan.migrationId(),
            plan.fileId(),
            payload);
      }
    } catch (RuntimeException error) {
      org.slf4j.LoggerFactory.getLogger(FileEventLogger.class)
          .error("File event could not be persisted");
    }
  }
}
