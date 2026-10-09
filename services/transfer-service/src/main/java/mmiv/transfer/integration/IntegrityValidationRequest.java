package mmiv.transfer.integration;

/** SHA-256 of the exact bytes sent to S3; local paths cannot cross service boundaries. */
public record IntegrityValidationRequest(String transferId, String bucket,
        String objectKey, String sourceChecksum) {}
