package mmiv.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import mmiv.csv.MetadataCsvRecord;
import mmiv.csv.MetadataCsvService;
import mmiv.model.DiscoveryResult;
import mmiv.model.DiscoveryStatus;
import mmiv.model.ManifestMatchResult;
import mmiv.model.MediaMetadata;
import mmiv.model.MigrationFileInventory;
import org.springframework.stereotype.Service;

@Service
public class ManifestDiscoveryService {

  private final MetadataCsvService csvService;
  private final DiscoveryService discoveryService;

  public ManifestDiscoveryService(
      MetadataCsvService csvService, DiscoveryService discoveryService) {
    this.csvService = csvService;
    this.discoveryService = discoveryService;
  }

  /**
   * Validate the CSV, discover actual files, and match manifest records against the inventory.
   *
   * <p>No database writes occur in this method.
   */
  public List<ManifestMatchResult> matchManifest(Path csvPath, Path rootDirectory)
      throws IOException {

    // Validate CSV before scanning files.
    List<MetadataCsvRecord> records = csvService.parseValidatedCsv(csvPath);

    MigrationFileInventory inventory = discoveryService.discoverDirectory(rootDirectory);

    Map<String, List<DiscoveryResult>> filesByName =
        inventory.getFiles().stream()
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
