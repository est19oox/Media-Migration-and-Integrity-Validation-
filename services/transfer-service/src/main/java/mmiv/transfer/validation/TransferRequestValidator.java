package mmiv.transfer.validation;

import mmiv.transfer.dto.TransferRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;

@Component
public class TransferRequestValidator {
    private final Path mediaRoot;
    public TransferRequestValidator(@Value("${mmiv.media.root:/media}") String root) {
        mediaRoot = Path.of(root).toAbsolutePath().normalize();
    }
    public void validate(TransferRequest request) {
        if (request == null) throw new IllegalArgumentException("Request is required");
        if (request.sourcePath() == null || request.sourcePath().isBlank()) throw new IllegalArgumentException("sourcePath is required");
        if (request.destinationKey() == null || request.destinationKey().isBlank()) throw new IllegalArgumentException("destinationKey is required");
        if (request.destinationKey().startsWith("/")) throw new IllegalArgumentException("destinationKey must be relative");
        resolveSource(request.sourcePath());
    }
    public Path resolveSource(String sourcePath) {
        try {
            Path root = mediaRoot.toRealPath();
            Path candidate = Path.of(sourcePath);
            Path source = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).toRealPath();
            if (!source.startsWith(root) || !Files.isRegularFile(source) || !Files.isReadable(source)) {
                throw new IllegalArgumentException("Source must be a readable file within the media directory");
            }
            return source;
        } catch (IOException | InvalidPathException e) {
            throw new IllegalArgumentException("Source file is unavailable or its path is invalid", e);
        }
    }
}
