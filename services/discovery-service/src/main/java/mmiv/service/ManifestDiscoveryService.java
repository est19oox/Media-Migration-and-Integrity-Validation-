package mmiv.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import mmiv.csv.MetadataCsvRecord;
import mmiv.csv.MetadataCsvService;
import mmiv.model.ManifestMatchResult;
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

    return inventory.matchManifest(records);
  }
}
