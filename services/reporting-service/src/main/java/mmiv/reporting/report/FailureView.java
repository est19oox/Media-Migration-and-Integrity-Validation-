package mmiv.reporting.report;

import java.util.Objects;

public record FailureView(String stage, String code, String message) {

  public FailureView {
    requireNonBlank(stage, "stage");
    requireNonBlank(code, "code");
    requireNonBlank(message, "message");
  }

  private static void requireNonBlank(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " must not be null");

    if (value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }
}
