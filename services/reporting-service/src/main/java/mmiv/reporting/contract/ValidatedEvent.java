package mmiv.reporting.contract;

import java.util.Arrays;
import java.util.Objects;

public record ValidatedEvent(EventEnvelope envelope, byte[] originalBody, String payloadSha256) {
  public ValidatedEvent {
    Objects.requireNonNull(envelope, "envelope must not be null");
    Objects.requireNonNull(originalBody, "originalBody must not be null");
    Objects.requireNonNull(payloadSha256, "payloadSha256 must not be null");

    originalBody = Arrays.copyOf(originalBody, originalBody.length);
  }

  @Override
  public byte[] originalBody() {
    return Arrays.copyOf(originalBody, originalBody.length);
  }
}
