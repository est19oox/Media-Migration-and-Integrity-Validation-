package mmiv.transfer.validation;

import mmiv.transfer.dto.TransferRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Locale;

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

    public String validateUpload(MultipartFile file, long maxBytes) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("file is required and must not be empty");
        if (file.getSize() > maxBytes) throw new IllegalArgumentException("File exceeds the upload size limit");
        String name = file.getOriginalFilename();
        // Discard client directory names, including Windows fakepath prefixes.
        if (name == null) throw new IllegalArgumentException("File name is required");
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank() || name.length() > 200 || !name.matches("[\\p{L}\\p{N}._ ()-]+")
                || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid file name");
        }
        uploadContentType(name);
        return name;
    }

    public String uploadContentType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) throw new IllegalArgumentException("Unsupported file type");
        return switch (fileName.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "mp4" -> "video/mp4";
            case "mp3" -> "audio/mpeg";
            case "txt" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> throw new IllegalArgumentException("Unsupported file type");
        };
    }
}
