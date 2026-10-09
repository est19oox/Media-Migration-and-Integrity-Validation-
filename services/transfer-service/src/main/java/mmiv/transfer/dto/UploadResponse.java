package mmiv.transfer.dto;

/** S3 upload and integrity validation are separate outcomes. */
public record UploadResponse(String bucket, String key, String fileName,
        String mediaType, long sizeBytes, String sha256, String fileId,
        String transferId, String validationResult, boolean success, String message, S3ObjectReference objectReference, String cleanupStatus) {
    public UploadResponse(String bucket, String key, String fileName, String mediaType,
            long sizeBytes, String sha256, String fileId, String transferId,
            String validationResult, boolean success, String message) {
        this(bucket, key, fileName, mediaType, sizeBytes, sha256, fileId, transferId,
                validationResult, success, message, null, "NOT_ATTEMPTED");
    }
    public UploadResponse(String bucket, String key, String fileName,
            String mediaType, long sizeBytes, String sha256) {
        this(bucket, key, fileName, mediaType, sizeBytes, sha256, mmiv.transfer.files.FileIds.encode(key));
    }
    public UploadResponse(String bucket, String key, String fileName,
            String mediaType, long sizeBytes, String sha256, String fileId) {
        this(bucket, key, fileName, mediaType, sizeBytes, sha256, fileId,
                null, "NOT_REQUESTED", false, "Uploaded; integrity has not been checked");
    }
}
