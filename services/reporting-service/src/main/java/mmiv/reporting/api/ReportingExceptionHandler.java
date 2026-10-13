package mmiv.reporting.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ReportingExceptionHandler {

  @ExceptionHandler(ReportNotFoundException.class)
  public ResponseEntity<ApiError> reportNotFound(ReportNotFoundException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError("REPORT_NOT_FOUND", exception.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> invalidRequest(IllegalArgumentException exception) {
    return ResponseEntity.badRequest()
        .body(new ApiError("INVALID_REQUEST", exception.getMessage()));
  }

  public record ApiError(String code, String message) {}
}
