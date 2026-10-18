package mmiv.integration;

import java.util.List;
import java.util.UUID;
import mmiv.model.MetadataPlan;

public record BatchAllocationResponse(UUID migrationId, List<MetadataPlan> plans) {
  public BatchAllocationResponse {
    plans = List.copyOf(plans);
  }
}
