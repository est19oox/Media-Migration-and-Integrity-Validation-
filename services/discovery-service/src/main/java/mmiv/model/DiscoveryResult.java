package mmiv.model;

import java.nio.file.Path;

public class DiscoveryResult {

    private final Path filePath;
    private final DiscoveryStatus status;
    private final MediaMetadata metadata;
    private final DiscoveryFailureReason reason;
    private final String message;

    public DiscoveryResult(
            Path filePath,
            DiscoveryStatus status,
            MediaMetadata metadata,
            DiscoveryFailureReason reason,
            String message) {

        this.filePath = filePath;
        this.status = status;
        this.metadata = metadata;
        this.reason = reason;
        this.message = message;
    }

    public static DiscoveryResult accepted(
            Path filePath,
            MediaMetadata metadata) {

        return new DiscoveryResult(
                filePath,
                DiscoveryStatus.ACCEPTED,
                metadata,
                null,
                null
        );
    }

    public static DiscoveryResult skipped(
            Path filePath,
            DiscoveryFailureReason reason,
            String message) {

        return new DiscoveryResult(
                filePath,
                DiscoveryStatus.SKIPPED,
                null,
                reason,
                message
        );
    }

    public static DiscoveryResult failed(
            Path filePath,
            DiscoveryFailureReason reason,
            String message) {

        return new DiscoveryResult(
                filePath,
                DiscoveryStatus.FAILED,
                null,
                reason,
                message
        );
    }

    public Path getFilePath() {
        return filePath;
    }

    public DiscoveryStatus getStatus() {
        return status;
    }

    public MediaMetadata getMetadata() {
        return metadata;
    }

    public DiscoveryFailureReason getReason() {
        return reason;
    }

    public String getMessage() {
        return message;
    }
}