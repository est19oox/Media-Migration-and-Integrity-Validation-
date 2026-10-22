package mmiv.transfer.integration;

import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import mmiv.transfer.port.TransferCoordination;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Coordinate folder mutations and admitted transfers through a Transfer-owned port. */
@Component
public class FolderAccessGuard {
  private final ConcurrentMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
  private final TransferCoordination distributed;

  public FolderAccessGuard() {
    this.distributed = null;
  }

  @Autowired
  public FolderAccessGuard(
      ObjectProvider<TransferCoordination> coordinator,
      @Value("${mmiv.coordination.required:false}") boolean required) {
    this.distributed = coordinator.getIfAvailable();
    if (required && distributed == null)
      throw new IllegalStateException("Distributed Transfer coordination is required");
  }

  public Scope read(String folder) {
    return acquire(folder, false);
  }

  public Scope write(String folder) {
    return acquire(folder, true);
  }

  private Scope acquire(String folder, boolean write) {
    if (folder == null || folder.isBlank()) throw new IllegalArgumentException("Choose a folder");
    if (distributed != null) {
      var scope = distributed.folder(folder);
      return scope::close;
    }
    var gate = locks.computeIfAbsent(folder, k -> new ReentrantReadWriteLock(true));
    Lock lock = write ? gate.writeLock() : gate.readLock();
    lock.lock();
    return lock::unlock;
  }

  public interface Scope extends AutoCloseable {
    void close();
  }
}
