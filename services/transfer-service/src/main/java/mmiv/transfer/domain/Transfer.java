package mmiv.transfer.domain;

import java.util.Objects;
import java.util.UUID;

/** Aggregate root for one synchronous transfer attempt. No infrastructure dependencies. */
public final class Transfer {
  public enum State {
    PREPARED,
    STORED,
    COMPLETED,
    CLEANUP_REQUIRED,
    CLEANED_UP,
    CLEANUP_FAILED
  }

  public enum ValidationStatus {
    PASSED,
    FAILED,
    UNAVAILABLE
  }

  public enum CleanupStatus {
    NOT_REQUIRED,
    DELETED,
    DELETE_FAILED
  }

  public record StoredObject(String bucket, String key, String eTag, String versionId) {
    public StoredObject {
      Objects.requireNonNull(bucket);
      Objects.requireNonNull(key);
    }
  }

  public record Validation(ValidationStatus status, String message) {
    public Validation {
      Objects.requireNonNull(status);
      Objects.requireNonNull(message);
    }
  }

  public record Cleanup(CleanupStatus status, String message) {
    public Cleanup {
      Objects.requireNonNull(status);
      Objects.requireNonNull(message);
    }
  }

  private final UUID id;
  private State state = State.PREPARED;
  private StoredObject stored;
  private Validation validation;
  private Cleanup cleanup;

  public Transfer(UUID id) {
    this.id = Objects.requireNonNull(id);
  }

  public void recordStorage(StoredObject receipt) {
    require(State.PREPARED);
    stored = Objects.requireNonNull(receipt);
    state = State.STORED;
  }

  public void recordValidation(Validation result) {
    require(State.STORED);
    validation = Objects.requireNonNull(result);
    if (result.status() == ValidationStatus.PASSED) {
      cleanup = new Cleanup(CleanupStatus.NOT_REQUIRED, "No cleanup required");
      state = State.COMPLETED;
    } else {
      state = State.CLEANUP_REQUIRED;
    }
  }

  public void recordCleanup(Cleanup result) {
    require(State.CLEANUP_REQUIRED);
    Objects.requireNonNull(result);
    if (result.status() == CleanupStatus.NOT_REQUIRED) {
      throw new IllegalArgumentException("Unsuccessful validation requires cleanup");
    }
    cleanup = result;
    state = result.status() == CleanupStatus.DELETED ? State.CLEANED_UP : State.CLEANUP_FAILED;
  }

  public UUID id() {
    return id;
  }

  public State state() {
    return state;
  }

  public boolean requiresCleanup() {
    return state == State.CLEANUP_REQUIRED;
  }

  public boolean success() {
    return state == State.COMPLETED;
  }

  public StoredObject stored() {
    if (stored == null) throw new IllegalStateException("Transfer has not been stored");
    return stored;
  }

  public Validation validation() {
    if (validation == null) throw new IllegalStateException("Transfer has not been validated");
    return validation;
  }

  public Cleanup cleanup() {
    if (cleanup == null) throw new IllegalStateException("Transfer cleanup has not been resolved");
    return cleanup;
  }

  public String message() {
    return validation().message() + ". " + cleanup().message();
  }

  private void require(State expected) {
    if (state != expected)
      throw new IllegalStateException("Expected " + expected + " but transfer is " + state);
  }
}
