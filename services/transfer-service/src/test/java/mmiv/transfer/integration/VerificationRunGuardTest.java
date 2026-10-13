package mmiv.transfer.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class VerificationRunGuardTest {
  private MetadataClient.Plan plan(String owner, UUID run) {
    var id = UUID.randomUUID();
    return new MetadataClient.Plan(
        id,
        id,
        UUID.randomUUID(),
        owner,
        "bucket",
        "bucket",
        "migrations/" + id + "/a.txt",
        null,
        "a.txt",
        "text/plain",
        1,
        "UPLOAD",
        "a.txt",
        run);
  }

  @Test
  void cleanupWaitsForTransfersThenRejectsLateWritesButCanBeRetried() throws Exception {
    var guard = new VerificationRunGuard();
    var run = UUID.randomUUID();
    var plan = plan("owner", run);
    var scope = guard.acquire(plan);
    var executor = Executors.newSingleThreadExecutor();
    boolean released = false;
    try {
      var entered = new CountDownLatch(1);
      var cleanup =
          executor.submit(
              () -> {
                entered.countDown();
                guard.cleanup("owner", run, () -> {});
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> cleanup.get(50, TimeUnit.MILLISECONDS));
      scope.close();
      released = true;
      cleanup.get(2, TimeUnit.SECONDS);
      assertThrows(
          org.springframework.web.server.ResponseStatusException.class, () -> guard.acquire(plan));
      assertDoesNotThrow(() -> guard.cleanup("owner", run, () -> {}));
      try (var other = guard.acquire(plan("other-owner", run))) {}
    } finally {
      if (!released) scope.close();
      executor.shutdownNow();
      executor.awaitTermination(2, TimeUnit.SECONDS);
    }
  }
}
