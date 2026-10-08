package mmiv.transfer.dto;

/** Upload success only; integrity validation is not enabled yet. */
public record UploadResponse(String bucket, String key, String fileName,
                             String mediaType, long sizeBytes, String sha256, String fileId) {
    public UploadResponse(String bucket, String key, String fileName,
            String mediaType, long sizeBytes, String sha256) {
        this(bucket, key, fileName, mediaType, sizeBytes, sha256,
                mmiv.transfer.files.FileIds.encode(key));
    }
}
