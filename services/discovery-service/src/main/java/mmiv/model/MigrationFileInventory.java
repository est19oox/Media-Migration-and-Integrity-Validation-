package mmiv.model;

import java.util.List;

public class MigrationFileInventory {

  private final List<DiscoveryResult> files;
  private final DiscoverySummary summary;

  public MigrationFileInventory(List<DiscoveryResult> files) {

    this.files = List.copyOf(files);
    this.summary = DiscoverySummary.from(this.files);
  }

  public List<DiscoveryResult> getFiles() {
    return files;
  }

  public DiscoverySummary getSummary() {
    return summary;
  }

  public List<MediaMetadata> getAcceptedFiles() {

    return files.stream()
        .filter(result -> result.getStatus() == DiscoveryStatus.ACCEPTED)
        .map(DiscoveryResult::getMetadata)
        .toList();
  }
}
