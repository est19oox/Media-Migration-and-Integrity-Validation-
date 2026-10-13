package mmiv.transfer.service;

import java.time.Instant;
import java.util.UUID;
import mmiv.transfer.dto.*;
import mmiv.transfer.factory.TransferStrategyFactory;
import mmiv.transfer.files.FileIds;
import mmiv.transfer.integration.IntegrityValidationGateway;
import mmiv.transfer.logging.FileEventLogger;
import mmiv.transfer.strategy.TransferBackend;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TransferService {
  private final TransferSourcePreparer preparer;
  private final TransferStrategyFactory factory;
  private final IntegrityValidationGateway integrity;
  private final S3ObjectCleanup cleanup;
  private final FileEventLogger logs;

  public TransferService(
      TransferSourcePreparer preparer,
      TransferStrategyFactory factory,
      IntegrityValidationGateway integrity,
      S3ObjectCleanup cleanup,
      FileEventLogger logs) {
    this.preparer = preparer;
    this.factory = factory;
    this.integrity = integrity;
    this.cleanup = cleanup;
    this.logs = logs;
  }

  public TransferResponse transfer(TransferRequest request) {
    Instant startedAt = Instant.now();
    try (PreparedTransfer source = preparer.prepare(request)) {
      var completed = execute(source);
      var stored = completed.stored();
      var outcome = completed.outcome();
      var result =
          new TransferResponse(
              stored.bucket(),
              stored.key(),
              source.contentType(),
              source.sizeBytes(),
              FileIds.encode(stored.key()),
              source.sha256(),
              completed.transferId(),
              outcome.validationResult(),
              outcome.success(),
              completed.message(),
              stored.objectReference(),
              completed.removal().status());
      logs.transfer(result, startedAt);
      return result;
    } catch (RuntimeException error) {
      logs.failure(
          "TRANSFER", null, request == null ? null : request.destinationKey(), error, startedAt);
      throw error;
    }
  }

  /** Shared storage, integrity retry, and guarded cleanup workflow for both input sources. */
  private CompletedTransfer execute(PreparedTransfer source) {
    // Metadata currently assigns S3 destinations; clients cannot select a transport or target.
    var stored = factory.getStrategy(TransferBackend.S3).transfer(source);
    String transferId = UUID.randomUUID().toString();
    var outcome = integrity.validate(transferId, stored.bucket(), stored.key(), source.sha256());
    var removal =
        outcome.success()
            ? new S3ObjectCleanup.Result("NOT_REQUIRED", "No cleanup required")
            : cleanup.delete(stored.bucket(), stored.key(), stored.objectReference());
    return new CompletedTransfer(stored, transferId, outcome, removal);
  }

  private record CompletedTransfer(
      StoredTransfer stored,
      String transferId,
      IntegrityValidationGateway.Outcome outcome,
      S3ObjectCleanup.Result removal) {
    String message() {
      return outcome.message() + ". " + removal.message();
    }
  }

  public UploadResponse upload(MultipartFile file) {
    Instant startedAt = Instant.now();
    String fileName;
    try {
      fileName = preparer.validateUpload(file);
    } catch (RuntimeException error) {
      logs.failure(
          "UPLOAD", file == null ? null : file.getOriginalFilename(), null, error, startedAt);
      throw error;
    }
    return uploadToKey(file, fileName, "uploads/" + UUID.randomUUID() + "/" + fileName, startedAt);
  }

  public UploadResponse uploadAssigned(MultipartFile file, String fileName, String key) {
    return uploadToKey(file, fileName, key);
  }

  UploadResponse uploadToKey(MultipartFile file, String fileName, String key) {
    return uploadToKey(file, fileName, key, Instant.now());
  }

  private UploadResponse uploadToKey(
      MultipartFile file, String fileName, String key, Instant startedAt) {
    try (PreparedTransfer source = preparer.prepare(file, fileName, key)) {
      var completed = execute(source);
      var stored = completed.stored();
      var outcome = completed.outcome();
      var result =
          new UploadResponse(
              stored.bucket(),
              stored.key(),
              source.fileName(),
              source.contentType(),
              source.sizeBytes(),
              source.sha256(),
              FileIds.encode(stored.key()),
              completed.transferId(),
              outcome.validationResult(),
              outcome.success(),
              completed.message(),
              stored.objectReference(),
              completed.removal().status());
      logs.upload(result, startedAt);
      return result;
    } catch (RuntimeException error) {
      logs.failure("UPLOAD", fileName, key, error, startedAt);
      throw error;
    }
  }
}
