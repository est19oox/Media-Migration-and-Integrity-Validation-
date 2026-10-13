package mmiv.user_access_service.repository;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
@Repository
@Profile("test")
public class InMemoryFolderRepository implements FolderRepository {
  private final java.util.Set<String> folders = new java.util.concurrent.ConcurrentSkipListSet<>();
  public List<String> list() { return List.copyOf(folders); }
  public boolean exists(String name) { return folders.contains(name); }
  public void create(String name) { if(!FolderRepository.valid(name)) throw new IllegalArgumentException("Invalid folder name"); folders.add(name); }
}
