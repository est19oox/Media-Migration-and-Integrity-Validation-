package mmiv.integration;

import java.util.List;
import java.util.UUID;

public record BatchAllocationResponse(UUID migrationId, List<MetadataPlan> plans) {
  public BatchAllocationResponse {
    plans = List.copyOf(plans);
  }
}
