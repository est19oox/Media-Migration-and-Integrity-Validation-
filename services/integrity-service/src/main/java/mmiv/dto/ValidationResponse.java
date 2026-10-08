package mmiv.dto;

public record ValidationResponse(
        String bucket,
        String objectKey,
        String algorithm,
        String sourceChecksum,
        String destinationChecksum,
        boolean valid
) {
}