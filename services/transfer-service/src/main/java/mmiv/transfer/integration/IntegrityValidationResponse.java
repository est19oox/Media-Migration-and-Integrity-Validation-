package mmiv.transfer.integration;

/** Boolean is nullable so an omitted valid field cannot be mistaken for a mismatch. */
public record IntegrityValidationResponse(String transferId, String bucket, String objectKey,
        String algorithm, String sourceChecksum, String destinationChecksum,
        Boolean valid, String validationResult) {}
