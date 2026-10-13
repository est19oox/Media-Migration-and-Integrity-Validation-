package mmiv.user_access_service.repository;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
@Repository
@Profile("test")
public class InMemoryFolderRepository implements FolderRepository {
  private final UserRepository users;
  public InMemoryFolderRepository(UserRepository users){this.users=users;}
  private final java.util.Set<String> folders = new java.util.concurrent.ConcurrentSkipListSet<>();
  public List<String> list() { return folders.stream().filter(n->!FolderRepository.verification(n)).toList(); }
  public boolean lock(String name){return folders.contains(name);}
  public void delete(String name){users.searchUsers("").forEach(u->users.revokeDestinationAccess(u.getUserId(),name));folders.remove(name);}
  public boolean deleteVerification(String name,String owner){delete(name);return true;}
  public boolean exists(String name) { return folders.contains(name); }
  public void create(String name) { if(!FolderRepository.valid(name)) throw new IllegalArgumentException("Invalid folder name"); folders.add(name); }
}
