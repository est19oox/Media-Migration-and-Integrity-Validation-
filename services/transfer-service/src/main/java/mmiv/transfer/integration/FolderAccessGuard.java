package mmiv.transfer.integration;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import org.springframework.stereotype.Component;
/** Coordinate folder mutations and admitted transfers in the single deployed Transfer instance. */
@Component
public class FolderAccessGuard {
  private final ConcurrentMap<String,ReentrantReadWriteLock> locks=new ConcurrentHashMap<>();
  public Scope read(String folder){return acquire(folder,false);}
  public Scope write(String folder){return acquire(folder,true);}
  private Scope acquire(String folder,boolean write){if(folder==null||folder.isBlank())throw new IllegalArgumentException("Choose a folder");var gate=locks.computeIfAbsent(folder,k->new ReentrantReadWriteLock(true));Lock lock=write?gate.writeLock():gate.readLock();lock.lock();return lock::unlock;}
  public interface Scope extends AutoCloseable{void close();}
}
