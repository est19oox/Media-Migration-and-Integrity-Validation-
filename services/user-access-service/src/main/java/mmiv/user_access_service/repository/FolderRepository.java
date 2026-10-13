package mmiv.user_access_service.repository;

import java.util.List;

public interface FolderRepository {
  List<String> list();
  boolean exists(String name);
  void create(String name);
  static boolean valid(String name) {
    return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,79}")
        && !name.endsWith(" ") && !name.endsWith(".") && !name.equals("migrations") && !name.equals("upload");
  }
}
