package mmiv.transfer.integration;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import mmiv.transfer.port.TransferCoordination;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/** Shared per-folder and per-verification-run mutex for ECS Transfer replicas. */
@Component
@ConditionalOnProperty(prefix = "mmiv.coordination", name = "table")
public class DynamoCoordination implements TransferCoordination {
  private static final long LEASE_SECONDS = 90;
  private final DynamoDbClient client;
  private final String table;
  private final Duration wait;
  private final ScheduledExecutorService renewals =
      Executors.newScheduledThreadPool(
          4,
          task -> {
            Thread thread = new Thread(task, "mmiv-dynamo-lock-renewal");
            thread.setDaemon(true);
            return thread;
          });

  @Autowired
  public DynamoCoordination(
      @Value("${aws.region:ap-southeast-1}") String region,
      @Value("${mmiv.coordination.table}") String table,
      @Value("${mmiv.coordination.wait-seconds:60}") long waitSeconds) {
    this(
        DynamoDbClient.builder().region(Region.of(region)).build(),
        table,
        Duration.ofSeconds(waitSeconds));
  }

  DynamoCoordination(DynamoDbClient client, String table, Duration wait) {
    if (table == null || table.isBlank())
      throw new IllegalArgumentException("Coordination table is required");
    this.client = client;
    this.table = table;
    this.wait = wait;
    client.describeTable(DescribeTableRequest.builder().tableName(table).build());
  }

  @Override
  public TransferCoordination.Scope folder(String folder) {
    return acquire("folder#" + folder);
  }

  @Override
  public TransferCoordination.VerificationScope verificationRun(String owner, UUID run) {
    return acquire("run#" + owner + "#" + run);
  }

  private Scope acquire(String key) {
    String owner = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + wait.toNanos();
    while (true) {
      long now = epochSeconds();
      try {
        client.updateItem(
            UpdateItemRequest.builder()
                .tableName(table)
                .key(key(key))
                .updateExpression("SET lockOwner = :owner, leaseUntil = :lease")
                .conditionExpression("attribute_not_exists(lockOwner) OR leaseUntil < :now")
                .expressionAttributeValues(
                    Map.of(
                        ":owner", text(owner),
                        ":lease", number(now + LEASE_SECONDS),
                        ":now", number(now)))
                .build());
        Scope scope = new Scope(key, owner, now + LEASE_SECONDS);
        scope.renewal = renewals.scheduleWithFixedDelay(() -> renew(scope), 5, 5, TimeUnit.SECONDS);
        return scope;
      } catch (ConditionalCheckFailedException busy) {
        if (System.nanoTime() >= deadline)
          throw new ResponseStatusException(
              HttpStatus.SERVICE_UNAVAILABLE, "Transfer coordination is busy");
        try {
          Thread.sleep(100);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new ResponseStatusException(
              HttpStatus.SERVICE_UNAVAILABLE, "Transfer coordination interrupted");
        }
      }
    }
  }

  private boolean isClosed(Scope scope) {
    scope.assertHeld();
    var item =
        client
            .getItem(
                GetItemRequest.builder()
                    .tableName(table)
                    .key(key(scope.key))
                    .consistentRead(true)
                    .build())
            .item();
    scope.assertHeld();
    return item.containsKey("closed") && Boolean.TRUE.equals(item.get("closed").bool());
  }

  private void closeRun(Scope scope) {
    scope.assertHeld();
    try {
      client.updateItem(
          UpdateItemRequest.builder()
              .tableName(table)
              .key(key(scope.key))
              .updateExpression("SET closed = :closed")
              .conditionExpression("lockOwner = :owner AND leaseUntil >= :now")
              .expressionAttributeValues(
                  Map.of(
                      ":closed", AttributeValue.builder().bool(true).build(),
                      ":owner", text(scope.owner),
                      ":now", number(epochSeconds())))
              .build());
    } catch (ConditionalCheckFailedException lost) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Transfer coordination lease lost");
    }
  }

  private void renew(Scope scope) {
    if (scope.released.get() || scope.lost.get()) return;
    long now = epochSeconds();
    try {
      client.updateItem(
          UpdateItemRequest.builder()
              .tableName(table)
              .key(key(scope.key))
              .updateExpression("SET leaseUntil = :lease")
              .conditionExpression("lockOwner = :owner AND leaseUntil >= :now")
              .expressionAttributeValues(
                  Map.of(
                      ":lease", number(now + LEASE_SECONDS),
                      ":owner", text(scope.owner),
                      ":now", number(now)))
              .build());
      scope.leaseUntil.set(now + LEASE_SECONDS);
    } catch (RuntimeException failure) {
      if (failure instanceof ConditionalCheckFailedException
          || now >= scope.leaseUntil.get() - 15) {
        scope.lost.set(true);
        scope.thread.interrupt();
      }
      // A transient DynamoDB failure is retried on the next renewal tick.
    }
  }

  private final class Scope implements TransferCoordination.VerificationScope {
    private final String key;
    private final String owner;
    private final Thread thread = Thread.currentThread();
    private final AtomicLong leaseUntil;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private ScheduledFuture<?> renewal;

    private Scope(String key, String owner, long leaseUntil) {
      this.key = key;
      this.owner = owner;
      this.leaseUntil = new AtomicLong(leaseUntil);
    }

    private void assertHeld() {
      if (released.get() || lost.get() || epochSeconds() > leaseUntil.get())
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Transfer coordination lease lost");
    }

    @Override
    public boolean isClosed() {
      return DynamoCoordination.this.isClosed(this);
    }

    @Override
    public void closeForCleanup() {
      DynamoCoordination.this.closeRun(this);
    }

    @Override
    public void close() {
      if (!released.compareAndSet(false, true)) return;
      renewal.cancel(false);
      if (lost.get() || epochSeconds() > leaseUntil.get())
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Transfer coordination lease lost");
      try {
        client.updateItem(
            UpdateItemRequest.builder()
                .tableName(table)
                .key(key(key))
                .updateExpression("REMOVE lockOwner, leaseUntil")
                .conditionExpression("lockOwner = :owner")
                .expressionAttributeValues(Map.of(":owner", text(owner)))
                .build());
      } catch (ConditionalCheckFailedException stolen) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Transfer coordination lease lost");
      }
    }
  }

  private Map<String, AttributeValue> key(String id) {
    return Map.of("pk", text(id));
  }

  private static AttributeValue text(String value) {
    return AttributeValue.builder().s(value).build();
  }

  private static AttributeValue number(long value) {
    return AttributeValue.builder().n(Long.toString(value)).build();
  }

  private static long epochSeconds() {
    return System.currentTimeMillis() / 1000;
  }

  @PreDestroy
  public void shutdown() {
    renewals.shutdownNow();
    client.close();
  }
}
