package mmiv.dto;

public record ValidationRequest(
        String bucket,
        String objectKey,
        String sourceChecksum
) {
}