package mmiv.transfer.domain;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Aggregate for one folder upload: all entries must form one valid folder tree. */
public final class FolderUploadManifest {
  public record Entry(String relativePath, String fileName, long sizeBytes) {}

  private final List<Entry> entries;

  public FolderUploadManifest(
      List<Entry> entries, int maxFiles, long maxTotalBytes, String storagePrefix) {
    if (entries == null || entries.isEmpty() || entries.size() > maxFiles)
      throw new IllegalArgumentException(
          "Provide matching files and relativePaths within the folder file limit");
    Set<String> seen = new HashSet<>();
    String root = null;
    long total = 0;
    for (Entry entry : entries) {
      String path = entry.relativePath();
      validatePath(path, storagePrefix);
      if (!seen.add(path)) throw new IllegalArgumentException("Duplicate relative path");
      String thisRoot = path.substring(0, path.indexOf('/'));
      if (root == null) root = thisRoot;
      if (!root.equals(thisRoot))
        throw new IllegalArgumentException("Select one root folder per request");
      if (!entry.fileName().equals(path.substring(path.lastIndexOf('/') + 1)))
        throw new IllegalArgumentException("Relative path must match the uploaded file name");
      if (entry.sizeBytes() < 0 || entry.sizeBytes() > maxTotalBytes - total)
        throw new IllegalArgumentException("Folder exceeds the total upload size limit");
      total += entry.sizeBytes();
    }
    this.entries = List.copyOf(entries);
  }

  public List<Entry> entries() {
    return entries;
  }

  private static void validatePath(String path, String prefix) {
    if (path == null
        || path.indexOf('/') <= 0
        || path.contains("\\")
        || (prefix + path).getBytes(StandardCharsets.UTF_8).length > 1024)
      throw new IllegalArgumentException("Invalid folder relative path");
    String[] parts = path.split("/", -1);
    if (parts.length > 64)
      throw new IllegalArgumentException("Folder nesting exceeds 64 path segments");
    for (String part : parts) {
      if (part.isBlank()
          || part.equals(".")
          || part.equals("..")
          || part.length() > 200
          || !part.matches("[\\p{L}\\p{N}._ ()-]+"))
        throw new IllegalArgumentException("Invalid folder path segment");
    }
  }
}
