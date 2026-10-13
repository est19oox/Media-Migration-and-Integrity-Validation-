package mmiv.integration;

import java.util.Set;

/** Names are unique within their logical directory; parentheses are ASCII. */
public final class UploadNames {
  private UploadNames() {}

  public static String available(String path, Set<String> used) {
    if (!used.contains(path)) return path;
    int slash = path.lastIndexOf('/');
    String directory = slash < 0 ? "" : path.substring(0, slash + 1),
        file = path.substring(slash + 1);
    int dot = file.lastIndexOf('.');
    String base = dot > 0 ? file.substring(0, dot) : file,
        extension = dot > 0 ? file.substring(dot) : "";
    for (int n = 2; n < Integer.MAX_VALUE; n++) {
      String candidate = directory + base + " (" + n + ")" + extension;
      if (!used.contains(candidate)) return candidate;
    }
    throw new IllegalArgumentException("No available file name");
  }
}
