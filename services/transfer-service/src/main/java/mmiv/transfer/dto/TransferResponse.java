package mmiv.transfer.dto;

public record TransferResponse(String bucket, String key, String mediaType, long sizeBytes) {
}
