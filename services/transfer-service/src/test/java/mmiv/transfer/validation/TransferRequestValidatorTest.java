package mmiv.transfer.validation;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.*;
import mmiv.transfer.dto.TransferRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class TransferRequestValidatorTest {
  @TempDir Path temp;
  Path media;
  TransferRequestValidator validator;

  @BeforeEach
  void setUp() throws IOException {
    media = Files.createDirectory(temp.resolve("media"));
    Files.writeString(media.resolve("sample.txt"), "sample");
    validator = new TransferRequestValidator(media.toString());
  }

  @Test
  void acceptsRelativePathWithinMediaDirectory() {
    assertDoesNotThrow(
        () -> validator.validate(new TransferRequest("sample.txt", "archive/sample.txt", null)));
  }

  @Test
  void acceptsAbsolutePathWithinMediaDirectory() throws IOException {
    assertEquals(
        media.resolve("sample.txt").toRealPath(),
        validator.resolveSource(media.resolve("sample.txt").toString()));
  }

  @Test
  void resolvesNestedFiles() throws IOException {
    Path file = Files.createDirectories(media.resolve("nested")).resolve("file.txt");
    Files.writeString(file, "data");
    assertEquals(file.toRealPath(), validator.resolveSource("nested/file.txt"));
  }

  @Test
  void rejectsNullRequest() {
    assertEquals(
        "Request is required",
        assertThrows(IllegalArgumentException.class, () -> validator.validate(null)).getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void rejectsMissingSourcePath(String source) {
    assertEquals(
        "sourcePath is required",
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(new TransferRequest(source, "key", null)))
            .getMessage());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void rejectsMissingDestinationKey(String key) {
    assertEquals(
        "destinationKey is required",
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(new TransferRequest("sample.txt", key, null)))
            .getMessage());
  }

  @Test
  void rejectsAbsoluteDestinationKey() {
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validate(new TransferRequest("sample.txt", "/archive/sample.txt", null)));
  }

  @Test
  void rejectsMissingFile() {
    assertThrows(IllegalArgumentException.class, () -> validator.resolveSource("missing.txt"));
  }

  @Test
  void rejectsDirectory() {
    assertThrows(IllegalArgumentException.class, () -> validator.resolveSource("."));
  }

  @Test
  void rejectsPathTraversal() throws IOException {
    Files.writeString(temp.resolve("outside.txt"), "private");
    assertThrows(IllegalArgumentException.class, () -> validator.resolveSource("../outside.txt"));
  }

  @Test
  void rejectsAbsolutePathOutsideRoot() throws IOException {
    Path outside = Files.writeString(temp.resolve("outside.txt"), "private");
    assertThrows(IllegalArgumentException.class, () -> validator.resolveSource(outside.toString()));
  }

  @Test
  void rejectsSiblingDirectoryWithMatchingNamePrefix() throws IOException {
    Path outside = Files.createDirectory(temp.resolve("media-other")).resolve("file.txt");
    Files.writeString(outside, "private");
    assertThrows(IllegalArgumentException.class, () -> validator.resolveSource(outside.toString()));
  }

  @Test
  void rejectsSymlinkEscapingRoot() throws IOException {
    Path outside = Files.writeString(temp.resolve("outside.txt"), "private");
    try {
      Files.createSymbolicLink(media.resolve("link.txt"), outside);
    } catch (UnsupportedOperationException | FileSystemException e) {
      assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
    }
    assertThrows(IllegalArgumentException.class, () -> validator.resolveSource("link.txt"));
  }

  @Test
  void rejectsInvalidPath() {
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.resolveSource("invalid" + (char) 0 + ".txt"));
  }

  @Test
  void rejectsUnavailableMediaRoot() {
    var unavailable = new TransferRequestValidator(temp.resolve("missing-root").toString());
    assertThrows(IllegalArgumentException.class, () -> unavailable.resolveSource("sample.txt"));
  }
}
