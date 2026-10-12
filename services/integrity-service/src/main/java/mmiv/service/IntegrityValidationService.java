package mmiv.service;

import mmiv.dto.ValidationResponse;
import mmiv.entity.IntegrityValidationLog;
import mmiv.repository.IntegrityValidationLogRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import mmiv.events.IntegrityEventPublisher;

@Service
public class IntegrityValidationService {

    private static final Logger logger =
            LoggerFactory.getLogger(IntegrityValidationService.class);

    private final S3ChecksumService s3ChecksumService;
    private final IntegrityValidationLogRepository validationLogRepository;
    private final IntegrityEventPublisher events;

    public IntegrityValidationService(
            S3ChecksumService s3ChecksumService,
            IntegrityValidationLogRepository validationLogRepository,
            IntegrityEventPublisher events) {

        this.s3ChecksumService = s3ChecksumService;
        this.validationLogRepository = validationLogRepository;
        this.events = events;
    }

    public ValidationResponse validate(
            String bucket,
            String objectKey,
            String sourceChecksum) throws IOException {
        return validate(bucket, objectKey, sourceChecksum, null);
    }

    public ValidationResponse validate(String bucket, String objectKey, String sourceChecksum, String transferId) throws IOException {
        Instant startedAt = Instant.now();

        logger.info(
                "Starting integrity validation: bucket={}, objectKey={}",
                bucket,
                objectKey
        );

        try {

            String destinationChecksum =
                    s3ChecksumService.calculateSHA256(
                            bucket,
                            objectKey
                    );

            boolean valid =
                    sourceChecksum.equalsIgnoreCase(destinationChecksum);

            String status = valid ? "PASSED" : "FAILED";

            logger.info(
                    "SHA-256 validation result: objectKey={}, sourceChecksum={}, destinationChecksum={}, status={}",
                    objectKey,
                    sourceChecksum,
                    destinationChecksum,
                    status
            );

            IntegrityValidationLog auditLog =
                    new IntegrityValidationLog();

            auditLog.setBucketName(bucket);
            auditLog.setObjectKey(objectKey);
            auditLog.setSourceChecksum(sourceChecksum);
            auditLog.setDestinationChecksum(destinationChecksum);
            auditLog.setAlgorithm("SHA-256");
            auditLog.setValidationStatus(status);
            auditLog.setValidatedAt(LocalDateTime.now());

            validationLogRepository.save(auditLog);

            logger.info(
                    "Integrity validation audit record saved: objectKey={}, status={}",
                    objectKey,
                    status
            );

            publishResult(transferId, status, startedAt);
            return new ValidationResponse(
                    bucket,
                    objectKey,
                    "SHA-256",
                    sourceChecksum,
                    destinationChecksum,
                    valid
            );

        } catch (IOException e) {

            IntegrityValidationLog auditLog =
                    new IntegrityValidationLog();

            auditLog.setBucketName(bucket);
            auditLog.setObjectKey(objectKey);
            auditLog.setSourceChecksum(sourceChecksum);
            auditLog.setAlgorithm("SHA-256");
            auditLog.setValidationStatus("ERROR");
            auditLog.setErrorMessage(e.getMessage());
            auditLog.setValidatedAt(LocalDateTime.now());

            validationLogRepository.save(auditLog);

            logger.error(
                    "Integrity validation error: objectKey={}",
                    objectKey,
                    e
            );

            publishResult(transferId, "ERROR", startedAt);
            throw e;
        }
    }
    private void publishResult(String transferId, String status, Instant startedAt) {
        if (transferId == null) return;
        UUID id;
        try { id = UUID.fromString(transferId); }
        catch (IllegalArgumentException e) { return; }
        Map<String, Object> data = new HashMap<>();
        data.put("transferId", transferId);
        data.put("status", status);
        data.put("checksumAlgorithm", "SHA-256");
        data.put("durationMs", Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis()));
        if (!"PASSED".equals(status)) {
            data.put("failure", Map.of("code", "FAILED".equals(status) ? "CHECKSUM_MISMATCH" : "VALIDATION_IO_ERROR",
                    "message", "Integrity validation did not pass", "retryable", "ERROR".equals(status)));
        }
        events.publish("INTEGRITY_VALIDATION_COMPLETED", "integrity.validation.completed",
                "integrity-service", id, IntegrityEventPublisher.fileId(id), data);
    }
}
