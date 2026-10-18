package mmiv.integration;

import java.util.Set;
import mmiv.model.MetadataPlan;

/** Compatibility entry point; naming policy is owned by MetadataPlan. */
public final class UploadNames {
  private UploadNames() {}

  public static String available(String path, Set<String> used) {
    return MetadataPlan.availableName(path, used);
  }
}
