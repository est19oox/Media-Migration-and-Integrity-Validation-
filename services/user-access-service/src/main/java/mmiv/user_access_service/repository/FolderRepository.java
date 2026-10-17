package mmiv.user_access_service.repository;

import java.util.List;

public interface FolderRepository {
  List<String> list();

  boolean exists(String name);

  void create(String name);

  boolean lock(String name);

  void delete(String name);

  boolean deleteVerification(String name, String owner);

  static boolean verification(String name) {
    return mmiv.user_access_service.model.Folder.verification(name);
  }

  static boolean valid(String name) {
    return mmiv.user_access_service.model.Folder.valid(name);
  }
}
