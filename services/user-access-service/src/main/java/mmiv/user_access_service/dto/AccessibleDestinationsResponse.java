package mmiv.user_access_service.dto;

import java.util.List;
import mmiv.user_access_service.model.Permission;

public record AccessibleDestinationsResponse(List<Destination> destinations) {

  public AccessibleDestinationsResponse {
    destinations = List.copyOf(destinations);
  }

  public record Destination(String destination, Permission permission) {}
}
