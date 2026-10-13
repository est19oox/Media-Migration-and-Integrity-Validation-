package mmiv.dto;

import java.time.Instant;
import java.util.List;

/**
 * Standard error response for Discovery Service REST APIs.
 *
 * @param timestamp time when the error occurred
 * @param status HTTP status code
 * @param error HTTP error description
 * @param message human-readable error message
 * @param validationErrors CSV validation error details
 * @param path request path
 */
public record ApiErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String message,
    List<ValidationErrorDetail> validationErrors,
    String path) {

  public ApiErrorResponse {
    validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
  }

  /** Individual CSV validation error. */
  public record ValidationErrorDetail(long rowNumber, String fieldName, String reason) {}
}
