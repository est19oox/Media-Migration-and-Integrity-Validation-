package mmiv.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import mmiv.csv.MetadataCsvRecord;

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

  public List<ManifestMatchResult> matchManifest(List<MetadataCsvRecord> records) {
    Map<String, List<DiscoveryResult>> filesByName =
        getFiles().stream()
            .collect(
                Collectors.groupingBy(result -> result.getFilePath().getFileName().toString()));

    List<ManifestMatchResult> results = new ArrayList<>();

    for (MetadataCsvRecord record : records) {

      List<DiscoveryResult> matches = filesByName.getOrDefault(record.fileName(), List.of());

      if (matches.isEmpty()) {

        results.add(
            ManifestMatchResult.mismatch(record, null, "FILE_NOT_FOUND", record.fileName()));

        continue;
      }

      if (matches.size() > 1) {

        results.add(
            ManifestMatchResult.mismatch(record, null, "AMBIGUOUS_FILE_NAME", record.fileName()));

        continue;
      }

      DiscoveryResult discoveryResult = matches.get(0);

      if (discoveryResult.getStatus() != DiscoveryStatus.ACCEPTED) {

        results.add(
            ManifestMatchResult.mismatch(
                record, discoveryResult.getMetadata(), "DISCOVERY_FAILED", record.fileName()));

        continue;
      }

      MediaMetadata actual = discoveryResult.getMetadata();

      // Check actual file size against CSV expected size.
      if (actual.getFileSize() != record.fileSize()) {

        results.add(
            ManifestMatchResult.mismatch(
                record, actual, "FILE_SIZE", String.valueOf(record.fileSize())));

        continue;
      }

      // Discovery currently stores extension as mediaType.
      // Compare it against CSV Format, not CSV Media Type.
      if (!actual.getMediaType().equalsIgnoreCase(record.format())) {

        results.add(ManifestMatchResult.mismatch(record, actual, "FILE_TYPE", record.format()));

        continue;
      }

      results.add(ManifestMatchResult.success(record, actual));
    }

    return results;
  }
}
