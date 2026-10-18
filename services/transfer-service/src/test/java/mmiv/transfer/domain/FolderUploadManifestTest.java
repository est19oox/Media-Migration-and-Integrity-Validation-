package mmiv.transfer.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class FolderUploadManifestTest {
  private static FolderUploadManifest.Entry entry(String path, long size) {
    return new FolderUploadManifest.Entry(path, path.substring(path.lastIndexOf('/') + 1), size);
  }

  @Test
  void nestedFilesFormOneValidFolderTree() {
    var entries = List.of(entry("Course/Week1/notes.pdf", 5), entry("Course/Week2/video.mp4", 7));
    assertEquals(entries, new FolderUploadManifest(entries, 500, 100, "uploads/id/").entries());
  }

  @Test
  void rejectsMixedRootsDuplicatePathsAndOversizedTree() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FolderUploadManifest(
                List.of(entry("A/x.txt", 5), entry("B/y.txt", 5)), 500, 100, "uploads/id/"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FolderUploadManifest(
                List.of(entry("A/x.txt", 5), entry("A/x.txt", 5)), 500, 100, "uploads/id/"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FolderUploadManifest(
                List.of(entry("A/x.txt", 60), entry("A/y.txt", 60)), 500, 100, "uploads/id/"));
  }
}
