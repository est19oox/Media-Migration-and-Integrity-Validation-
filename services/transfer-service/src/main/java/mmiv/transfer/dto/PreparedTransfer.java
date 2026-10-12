package mmiv.transfer.dto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server-owned byte snapshot. The caller must close it after synchronous storage completes. */
public record PreparedTransfer(Path path, String fileName, String key,
        String contentType, long sizeBytes, String sha256) implements AutoCloseable {
    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            org.slf4j.LoggerFactory.getLogger(PreparedTransfer.class)
                    .warn("Could not delete transfer temporary file");
        }
    }
}
