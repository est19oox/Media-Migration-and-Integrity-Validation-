package mmiv.transfer.dto;

public record TransferResponse(String bucket, String key, String mediaType, long sizeBytes, String fileId) {
    public TransferResponse(String bucket, String key, String mediaType, long sizeBytes) {
        this(bucket, key, mediaType, sizeBytes, mmiv.transfer.files.FileIds.encode(key));
    }
}
