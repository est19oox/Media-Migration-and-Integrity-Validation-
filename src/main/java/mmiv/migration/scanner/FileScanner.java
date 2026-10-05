package mmiv.migration.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class FileScanner {

    private final Set<String> supportedTypes;

    public FileScanner(Set<String> supportedTypes) {
        this.supportedTypes = supportedTypes;
    }

    public List<Path> scan(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .toList();
        }
    }

    private boolean isSupported(Path file) {
        String fileName = file.getFileName().toString();

        int dotIndex = fileName.lastIndexOf('.');

        if (dotIndex == -1) {
            return false;
        }

        String extension = fileName
                .substring(dotIndex + 1)
                .toLowerCase(java.util.Locale.ROOT);

        return supportedTypes.contains(extension);
    }
}