package mmiv.user_access_service.model;

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
}
