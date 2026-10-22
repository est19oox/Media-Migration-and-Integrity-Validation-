package mmiv.transfer.integration;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import mmiv.transfer.port.TransferCoordination;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Cleanup seals the run and waits for all previously admitted synchronous transfers. */
@Component
public class VerificationRunGuard {
  private final TransferCoordination distributed;

  public VerificationRunGuard() {
    this.distributed = null;
  }

  @Autowired
  public VerificationRunGuard(
      ObjectProvider<TransferCoordination> coordinator,
      @Value("${mmiv.coordination.required:false}") boolean required) {
    this.distributed = coordinator.getIfAvailable();
    if (required && distributed == null)
      throw new IllegalStateException("Distributed Transfer coordination is required");
  }

  private static class State {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    boolean closed;
  }

  private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

  public record Scope(Runnable release) implements AutoCloseable {
    public void close() {
      release.run();
    }
  }

  public Scope acquire(MetadataClient.Plan plan) {
    if (plan.verificationRunId() == null) return new Scope(() -> {});
    if (distributed != null) {
      var scope = distributed.verificationRun(plan.ownerId(), plan.verificationRunId());
      try {
        if (scope.isClosed())
          throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification run is closed");
        return new Scope(scope::close);
      } catch (RuntimeException error) {
        try {
          scope.close();
        } catch (RuntimeException closeFailure) {
          error.addSuppressed(closeFailure);
        }
        throw error;
      }
    }
    var state =
        states.computeIfAbsent(plan.ownerId() + ":" + plan.verificationRunId(), key -> new State());
    state.lock.readLock().lock();
    if (state.closed) {
      state.lock.readLock().unlock();
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Verification run is closed");
    }
    return new Scope(state.lock.readLock()::unlock);
  }

  public void cleanup(String owner, UUID run, Runnable operation) {
    if (distributed != null) {
      try (var scope = distributed.verificationRun(owner, run)) {
        scope.closeForCleanup();
        operation.run();
      }
      return;
    }
    var state = states.computeIfAbsent(owner + ":" + run, key -> new State());
    state.lock.writeLock().lock();
    try {
      state.closed = true;
      operation.run();
    } finally {
      state.lock.writeLock().unlock();
    }
  }
}
