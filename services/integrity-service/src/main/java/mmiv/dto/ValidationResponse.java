package mmiv.dto;

public record ValidationResponse(String bucket, String objectKey, String algorithm,
        String sourceChecksum, String destinationChecksum, boolean valid,
        String transferId, String validationResult) {
    public ValidationResponse(String bucket, String objectKey, String algorithm,
            String sourceChecksum, String destinationChecksum, boolean valid) {
        this(bucket, objectKey, algorithm, sourceChecksum, destinationChecksum, valid,
                null, valid ? "PASSED" : "FAILED");
    }
}
