package mmiv.transfer.service;

import mmiv.transfer.dto.TransferRequest;
import mmiv.transfer.dto.TransferResponse;
import mmiv.transfer.factory.TransferStrategyFactory;
import mmiv.transfer.strategy.TransferStrategy;
import mmiv.transfer.validation.TransferRequestValidator;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import mmiv.transfer.dto.UploadSource;
import mmiv.transfer.dto.UploadResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.UUID;
import java.time.Instant;
import mmiv.transfer.logging.FileEventLogger;
import mmiv.transfer.events.TransferEventPublisher;
import java.util.Map;
import java.time.Duration;

@Service
public class TransferService {
    private final TransferRequestValidator validator;
    private final TransferStrategyFactory factory;
    @Value("${mmiv.upload.max-size-bytes:104857600}")
    private long maxUploadBytes = 104857600;
    private final mmiv.transfer.integration.IntegrityValidationGateway integrity;
    private final S3ObjectCleanup cleanup;
    private final FileEventLogger logs;
    private final TransferEventPublisher events;

    public TransferService(TransferRequestValidator validator, TransferStrategyFactory factory,
            mmiv.transfer.integration.IntegrityValidationGateway integrity, S3ObjectCleanup cleanup, FileEventLogger logs, TransferEventPublisher events) {
        this.validator = validator;
        this.factory = factory;
        this.integrity = integrity;
        this.cleanup = cleanup;
        this.logs = logs;
        this.events = events;
    }

    public TransferResponse transfer(TransferRequest request) {
        Instant startedAt = Instant.now();
        try {
            validator.validate(request);
            TransferStrategy strategy = factory.getStrategy(request);
            TransferResponse uploaded = strategy.transfer(request);
            String transferId = UUID.randomUUID().toString();
            publishStored(transferId, startedAt);
            var outcome = integrity.validate(transferId, uploaded.bucket(), uploaded.key(), uploaded.sha256());
            var removal = finish(outcome.success(), uploaded.bucket(), uploaded.key(), uploaded.objectReference());
            var result = new TransferResponse(uploaded.bucket(), uploaded.key(), uploaded.mediaType(), uploaded.sizeBytes(),
                    uploaded.fileId(), uploaded.sha256(), transferId, outcome.validationResult(),
                    outcome.success(), outcome.message() + ". " + removal.message(),
                    uploaded.objectReference(), removal.status());
            logs.transfer(result, startedAt);
            return result;
        } catch (RuntimeException error) {
            logs.failure("TRANSFER", null, request == null ? null : request.destinationKey(), error, startedAt);
            throw error;
        }
    }

    private S3ObjectCleanup.Result finish(boolean success, String bucket, String key,
            mmiv.transfer.dto.S3ObjectReference reference) {
        return success ? new S3ObjectCleanup.Result("NOT_REQUIRED", "No cleanup required")
                : cleanup.delete(bucket, key, reference);
    }

    public UploadResponse upload(MultipartFile file) {
        Instant startedAt = Instant.now();
        String fileName;
        try {
            fileName = validator.validateUpload(file, maxUploadBytes);
        } catch (RuntimeException error) {
            logs.failure("UPLOAD", file == null ? null : file.getOriginalFilename(), null, error, startedAt);
            throw error;
        }
        return uploadToKey(file, fileName, "uploads/" + UUID.randomUUID() + "/" + fileName, startedAt);
    }

    UploadResponse uploadToKey(MultipartFile file, String fileName, String key) {
        return uploadToKey(file, fileName, key, Instant.now());
    }

    private UploadResponse uploadToKey(MultipartFile file, String fileName, String key, Instant startedAt) {
        Path temporary = null;
        try {
            String contentType = validator.uploadContentType(fileName);
            TransferStrategy strategy = factory.getStrategy(new TransferRequest(fileName, key, contentType));
            temporary = Files.createTempFile("mmiv-upload-", ".tmp");
            file.transferTo(temporary);
            long actualSize = Files.size(temporary);
            if (actualSize == 0 || actualSize > maxUploadBytes) {
                throw new IllegalArgumentException("File must be nonempty and within the upload size limit");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(temporary), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            UploadResponse response = strategy.transferUploaded(new UploadSource(
                    temporary, fileName, key, contentType, actualSize, sha256));
            String transferId = UUID.randomUUID().toString();
            publishStored(transferId, startedAt);
            var outcome = integrity.validate(transferId, response.bucket(), response.key(), sha256);
            var removal = finish(outcome.success(), response.bucket(), response.key(), response.objectReference());
            var result = new UploadResponse(response.bucket(), response.key(), response.fileName(), response.mediaType(),
                    response.sizeBytes(), sha256, response.fileId(), transferId, outcome.validationResult(),
                    outcome.success(), outcome.message() + ". " + removal.message(),
                    response.objectReference(), removal.status());
            logs.upload(result, startedAt);
            return result;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            var error = new IllegalStateException("Upload processing failed", e);
            logs.failure("UPLOAD", fileName, key, error, startedAt);
            throw error;
        } catch (RuntimeException error) {
            logs.failure("UPLOAD", fileName, key, error, startedAt);
            throw error;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) {
                    org.slf4j.LoggerFactory.getLogger(TransferService.class)
                            .warn("Could not delete upload temporary file");
                }
            }
        }
    }

    private void publishStored(String transferId, Instant startedAt) {
        UUID id = UUID.fromString(transferId);
        events.publish("FILE_TRANSFER_COMPLETED", "transfer.file.completed", "transfer-service",
                id, TransferEventPublisher.fileId(id), Map.of(
                        "transferId", transferId,
                        "status", "STORED",
                        "durationMs", Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis())));
    }
}
