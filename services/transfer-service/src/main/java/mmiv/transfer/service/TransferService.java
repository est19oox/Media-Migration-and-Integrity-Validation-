package mmiv.transfer.service;

import java.time.Instant;
import java.util.UUID;
import mmiv.transfer.domain.Transfer;
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
      var outcome = completed.validation();
      var result =
          new TransferResponse(
              stored.bucket(),
              stored.key(),
              source.contentType(),
              source.sizeBytes(),
              FileIds.encode(stored.key()),
              source.sha256(),
              completed.id().toString(),
              outcome.status().name(),
              completed.success(),
              completed.message(),
              objectReference(stored),
              completed.cleanup().status().name());
      logs.transfer(result, startedAt);
      return result;
    } catch (RuntimeException error) {
      logs.failure(
          "TRANSFER", null, request == null ? null : request.destinationKey(), error, startedAt);
      throw error;
    }
  }

  /** Coordinates I/O; the aggregate decides completion and whether cleanup is required. */
  private Transfer execute(PreparedTransfer source) {
    var transfer = new Transfer(UUID.randomUUID());
    // Metadata currently assigns S3 destinations; clients cannot select a transport or target.
    var stored = factory.getStrategy(TransferBackend.S3).transfer(source);
    var reference = stored.objectReference();
    transfer.recordStorage(
        new Transfer.StoredObject(
            stored.bucket(),
            stored.key(),
            reference == null ? null : reference.eTag(),
            reference == null ? null : reference.versionId()));
    var outcome =
        integrity.validate(
            transfer.id().toString(), stored.bucket(), stored.key(), source.sha256());
    transfer.recordValidation(
        new Transfer.Validation(
            Transfer.ValidationStatus.valueOf(outcome.validationResult()), outcome.message()));
    if (transfer.requiresCleanup()) {
      var removal = cleanup.delete(stored.bucket(), stored.key(), reference);
      transfer.recordCleanup(
          new Transfer.Cleanup(
              Transfer.CleanupStatus.valueOf(removal.status()), removal.message()));
    }
    return transfer;
  }

  private static S3ObjectReference objectReference(Transfer.StoredObject stored) {
    return stored.eTag() == null && stored.versionId() == null
        ? null
        : new S3ObjectReference(stored.eTag(), stored.versionId());
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
      var outcome = completed.validation();
      var result =
          new UploadResponse(
              stored.bucket(),
              stored.key(),
              source.fileName(),
              source.contentType(),
              source.sizeBytes(),
              source.sha256(),
              FileIds.encode(stored.key()),
              completed.id().toString(),
              outcome.status().name(),
              completed.success(),
              completed.message(),
              objectReference(stored),
              completed.cleanup().status().name());
      logs.upload(result, startedAt);
      return result;
    } catch (RuntimeException error) {
      logs.failure("UPLOAD", fileName, key, error, startedAt);
      throw error;
    }
  }
}
