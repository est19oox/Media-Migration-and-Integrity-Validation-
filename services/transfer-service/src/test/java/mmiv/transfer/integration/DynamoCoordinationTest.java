package mmiv.transfer.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mmiv.transfer.port.TransferCoordination;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoCoordinationTest {
  @Test
  void requiredCoordinationNeverFallsBackToLocalLocks() {
    @SuppressWarnings("unchecked")
    ObjectProvider<TransferCoordination> missing = mock(ObjectProvider.class);
    assertThrows(IllegalStateException.class, () -> new FolderAccessGuard(missing, true));
    assertThrows(IllegalStateException.class, () -> new VerificationRunGuard(missing, true));
  }

  @Test
  void twoReplicasSerializeTheSameFolderAndPreserveClosedRuns() throws Exception {
    var client = fakeDynamo();
    var first = new DynamoCoordination(client, "locks", Duration.ofSeconds(3));
    var second = new DynamoCoordination(client, "locks", Duration.ofSeconds(3));
    var executor = Executors.newSingleThreadExecutor();
    try {
      var held = first.folder("course");
      var waiting = executor.submit(() -> second.folder("course"));
      assertThrows(TimeoutException.class, () -> waiting.get(150, TimeUnit.MILLISECONDS));
      held.close();
      try (var next = waiting.get(2, TimeUnit.SECONDS)) {
        assertNotNull(next);
      }
      var runId = java.util.UUID.randomUUID();
      try (var run = first.verificationRun("owner", runId)) {
        run.closeForCleanup();
      }
      try (var run = second.verificationRun("owner", runId)) {
        assertTrue(run.isClosed());
      }
    } finally {
      executor.shutdownNow();
      first.shutdown();
      second.shutdown();
    }
  }

  private static DynamoDbClient fakeDynamo() {
    var client = mock(DynamoDbClient.class);
    var items = new HashMap<String, Map<String, AttributeValue>>();
    when(client.updateItem(any(UpdateItemRequest.class)))
        .thenAnswer(
            invocation -> {
              UpdateItemRequest request = invocation.getArgument(0);
              String key = request.key().get("pk").s();
              synchronized (items) {
                var item = items.computeIfAbsent(key, unused -> new HashMap<>());
                var values = request.expressionAttributeValues();
                String owner = item.containsKey("lockOwner") ? item.get("lockOwner").s() : null;
                long lease =
                    item.containsKey("leaseUntil") ? Long.parseLong(item.get("leaseUntil").n()) : 0;
                long now = values.containsKey(":now") ? Long.parseLong(values.get(":now").n()) : 0;
                if (request.updateExpression().startsWith("SET lockOwner")) {
                  if (owner != null && lease >= now) throw conditionalFailure();
                  item.put("lockOwner", values.get(":owner"));
                  item.put("leaseUntil", values.get(":lease"));
                } else {
                  if (!values.get(":owner").s().equals(owner)) throw conditionalFailure();
                  if (request.updateExpression().startsWith("SET leaseUntil")) {
                    if (lease < now) throw conditionalFailure();
                    item.put("leaseUntil", values.get(":lease"));
                  } else if (request.updateExpression().startsWith("SET closed")) {
                    if (lease < now) throw conditionalFailure();
                    item.put("closed", values.get(":closed"));
                  } else {
                    item.remove("lockOwner");
                    item.remove("leaseUntil");
                  }
                }
              }
              return UpdateItemResponse.builder().build();
            });
    when(client.getItem(any(GetItemRequest.class)))
        .thenAnswer(
            invocation -> {
              GetItemRequest request = invocation.getArgument(0);
              synchronized (items) {
                return GetItemResponse.builder()
                    .item(Map.copyOf(items.get(request.key().get("pk").s())))
                    .build();
              }
            });
    return client;
  }

  private static ConditionalCheckFailedException conditionalFailure() {
    return ConditionalCheckFailedException.builder().message("busy").build();
  }
}
