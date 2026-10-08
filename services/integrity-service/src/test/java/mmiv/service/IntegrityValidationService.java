package mmiv.service;

import mmiv.dto.ValidationResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class IntegrityValidationService {

    private static final Logger logger =
            LoggerFactory.getLogger(IntegrityValidationService.class);

    private final S3ChecksumService s3ChecksumService;

    public IntegrityValidationService(
            S3ChecksumService s3ChecksumService) {

        this.s3ChecksumService = s3ChecksumService;
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

        String destinationChecksum =
                s3ChecksumService.calculateSHA256(
                        bucket,
                        objectKey
                );

        logger.info(
                "SHA-256 calculated: objectKey={}, sourceChecksum={}, destinationChecksum={}",
                objectKey,
                sourceChecksum,
                destinationChecksum
        );

        boolean valid =
                sourceChecksum.equalsIgnoreCase(destinationChecksum);

        if (valid) {
            logger.info(
                    "Integrity validation PASSED: objectKey={}, algorithm=SHA-256",
                    objectKey
            );
        } else {
            logger.warn(
                    "Integrity validation FAILED: objectKey={}, algorithm=SHA-256",
                    objectKey
            );
        }

        return new ValidationResponse(
                bucket,
                objectKey,
                "SHA-256",
                sourceChecksum,
                destinationChecksum,
                valid
        );
    }
}