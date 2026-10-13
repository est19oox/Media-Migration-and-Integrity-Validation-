package mmiv.transfer.controller;

import mmiv.transfer.integration.MetadataClient;
import org.springframework.web.bind.annotation.*;

@RestController
public class DestinationController {
  private final MetadataClient metadata;

  public DestinationController(MetadataClient metadata) {
    this.metadata = metadata;
  }

  @GetMapping("/api/v1/destinations")
  public Object destinations() {
    return metadata.destinations();
  }
}
