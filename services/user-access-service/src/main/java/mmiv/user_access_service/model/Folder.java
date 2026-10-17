package mmiv.user_access_service.model;

/** Folder aggregate identity; grants remain owned by User. */
public record Folder(String name) {
  public Folder {
    if (!valid(name)) throw new IllegalArgumentException("Invalid folder name");
  }

  public static boolean verification(String name) {
    return name != null && (name.equals("verification") || name.startsWith("mmiv-verification-"));
  }

  public static boolean valid(String name) {
    return name != null
        && name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}")
        && !name.endsWith(" ")
        && !name.endsWith(".")
        && !name.equals("migrations")
        && !name.equals("upload");
  }

  public static boolean validAdministratorFolder(String name, String bucket) {
    return valid(name) && !verification(name) && !name.equals(bucket);
  }

  public boolean canBeCreatedByAdministrator(String bucket) {
    return !verification(name) && !name.equals(bucket);
  }
}
