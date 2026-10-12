package mmiv.user_access_service.model;

import java.util.Objects;

public class DestinationAccess {

  private final String destination;
  private final Permission permission;

  public DestinationAccess(String destination, Permission permission) {
    this.destination = destination;
    this.permission = permission;
  }

  public String getDestination() {
    return destination;
  }

  public Permission getPermission() {
    return permission;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof DestinationAccess that)) {
      return false;
    }
    return Objects.equals(destination, that.destination) && permission == that.permission;
  }

  @Override
  public int hashCode() {
    return Objects.hash(destination, permission);
  }
}
