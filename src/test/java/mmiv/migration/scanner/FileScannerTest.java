package mmiv.migration.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldFindFilesRecursively() throws IOException {
        // Arrange
        Path rootFile = Files.createFile(
                tempDir.resolve("document.pdf"));

        Path subDirectory = Files.createDirectory(
                tempDir.resolve("images"));

        Path nestedFile = Files.createFile(
                subDirectory.resolve("photo.png"));

        FileScanner scanner = new FileScanner(
                Set.of("pdf", "png"));

        // Act
        List<Path> files = scanner.scan(tempDir);

        // Assert
        assertEquals(2, files.size());
        assertTrue(files.contains(rootFile));
        assertTrue(files.contains(nestedFile));
    }

    @Test
    void shouldOnlyFindSupportedFileTypes() throws IOException {
        // Arrange
        Path pdfFile = Files.createFile(
                tempDir.resolve("document.pdf"));

        Path pngFile = Files.createFile(
                tempDir.resolve("image.png"));

        Files.createFile(
                tempDir.resolve("notes.txt"));

        FileScanner scanner = new FileScanner(
                Set.of("pdf", "png"));

        // Act
        List<Path> files = scanner.scan(tempDir);

        // Assert
        assertEquals(2, files.size());
        assertTrue(files.contains(pdfFile));
        assertTrue(files.contains(pngFile));
    }
}