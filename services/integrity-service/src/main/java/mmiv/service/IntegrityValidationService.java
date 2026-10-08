package mmiv.service;

import mmiv.dto.ValidationResponse;
import mmiv.entity.IntegrityValidationLog;
import mmiv.repository.IntegrityValidationLogRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;

@Service
public class IntegrityValidationService {

    private static final Logger logger =
            LoggerFactory.getLogger(IntegrityValidationService.class);

    private final S3ChecksumService s3ChecksumService;
    private final IntegrityValidationLogRepository validationLogRepository;

    public IntegrityValidationService(
            S3ChecksumService s3ChecksumService,
            IntegrityValidationLogRepository validationLogRepository) {

        this.s3ChecksumService = s3ChecksumService;
        this.validationLogRepository = validationLogRepository;
    }

    public ValidationResponse validate(
            String bucket,
            String objectKey,
            String sourceChecksum) throws IOException {

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

            throw e;
        }
    }
}