package mmiv.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.List;
import mmiv.csv.MetadataCsvValidationException;
import mmiv.dto.ApiErrorResponse;
import mmiv.dto.ApiErrorResponse.ValidationErrorDetail;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Global exception handling for Discovery Service REST APIs.
 *
 * <p>Converts application-specific exceptions into consistent JSON error responses without
 * overriding Spring MVC's built-in handling of HTTP errors such as 404 and 405.
 */
@RestControllerAdvice
public class DiscoveryExceptionHandler {

  /**
   * Handle invalid CSV manifests.
   *
   * <p>Missing CSV files return HTTP 404. Other CSV validation failures return HTTP 400.
   */
  @ExceptionHandler(MetadataCsvValidationException.class)
  public ResponseEntity<ApiErrorResponse> handleCsvValidation(
      MetadataCsvValidationException exception, HttpServletRequest request) {

    List<ValidationErrorDetail> errors =
        exception.getErrors().stream()
            .map(
                error ->
                    new ValidationErrorDetail(error.rowNumber(), error.fieldName(), error.reason()))
            .toList();

    boolean fileNotFound =
        exception.getErrors().size() == 1
            && exception.getErrors().stream()
                .allMatch(
                    error ->
                        "File".equals(error.fieldName())
                            && "CSV file does not exist".equals(error.reason()));

    HttpStatus status = fileNotFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;

    String message = fileNotFound ? "CSV manifest file not found" : "CSV validation failed";

    return buildResponse(status, message, errors, request.getRequestURI());
  }

  /** Handle missing filesystem resources. */
  @ExceptionHandler(NoSuchFileException.class)
  public ResponseEntity<ApiErrorResponse> handleFileNotFound(
      NoSuchFileException exception, HttpServletRequest request) {

    return buildResponse(
        HttpStatus.NOT_FOUND, "Requested file not found", List.of(), request.getRequestURI());
  }

  /** Handle general file processing failures. */
  @ExceptionHandler(IOException.class)
  public ResponseEntity<ApiErrorResponse> handleIOException(
      IOException exception, HttpServletRequest request) {

    return buildResponse(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "File processing failed",
        List.of(),
        request.getRequestURI());
  }

  /**
   * Handle Oracle and other database access failures.
   *
   * <p>Do not expose raw SQL or database exception messages.
   */
  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<ApiErrorResponse> handleDatabaseException(
      DataAccessException exception, HttpServletRequest request) {

    return buildResponse(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Database operation failed",
        List.of(),
        request.getRequestURI());
  }

  /**
   * Preserve explicitly declared HTTP status codes, including the 503 returned when the CSV
   * manifest path is not configured.
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiErrorResponse> handleResponseStatus(
      ResponseStatusException exception, HttpServletRequest request) {

    HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());

    if (status == null) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    }

    String message =
        exception.getReason() != null ? exception.getReason() : status.getReasonPhrase();

    return buildResponse(status, message, List.of(), request.getRequestURI());
  }

  /** Build a consistent API error response. */
  private ResponseEntity<ApiErrorResponse> buildResponse(
      HttpStatus status,
      String message,
      List<ValidationErrorDetail> validationErrors,
      String path) {

    ApiErrorResponse response =
        new ApiErrorResponse(
            Instant.now(),
            status.value(),
            status.getReasonPhrase(),
            message,
            validationErrors,
            path);

    return ResponseEntity.status(status).body(response);
  }
}
