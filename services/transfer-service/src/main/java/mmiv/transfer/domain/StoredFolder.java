package mmiv.transfer.domain;

import java.util.Arrays;

/** Logical folder boundary for recursive storage deletion, not an S3 directory object. */
public record StoredFolder(String name, String path) {
  public StoredFolder {
    if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}") || reserved(name))
      throw new IllegalArgumentException("Invalid folder");
    if (path != null
        && (path.isBlank()
            || path.length() > 900
            || Arrays.stream(path.split("/", -1))
                .anyMatch(
                    part ->
                        part.equals(".")
                            || part.equals("..")
                            || !part.matches("[\\p{L}\\p{N}._ ()-]{1,200}"))))
      throw new IllegalArgumentException("Invalid folder path");
  }

  public static StoredFolder forCreation(String name, String bucket) {
    var folder = new StoredFolder(name, null);
    if (name.endsWith(" ")
        || name.endsWith(".")
        || name.equals(bucket)
        || name.equals("migrations")
        || name.equals("upload")) throw new IllegalArgumentException("Invalid folder name");
    return folder;
  }

  public String prefix() {
    return name + "/";
  }

  public boolean contains(String key) {
    return key.startsWith(prefix()) && (path == null || relative(key, name).startsWith(path + "/"));
  }

  public boolean isRoot() {
    return path == null;
  }

  public static String relative(String key, String folder) {
    return key.substring(folder.length() + 1).replaceFirst("^migrations/[0-9a-fA-F-]{36}/", "");
  }

  public static boolean reserved(String folder) {
    return folder.equals("verification") || folder.startsWith("mmiv-verification-");
  }
}
