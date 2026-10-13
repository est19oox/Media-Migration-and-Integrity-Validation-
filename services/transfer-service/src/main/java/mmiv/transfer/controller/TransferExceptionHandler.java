package mmiv.transfer.controller;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class TransferExceptionHandler {
  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> integrationFailure(
      org.springframework.web.server.ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(Map.of("error", e.getReason() == null ? "Request rejected" : e.getReason()));
  }

  @ExceptionHandler(mmiv.transfer.files.FileRangeException.class)
  public ResponseEntity<Map<String, String>> invalidRange(
      mmiv.transfer.files.FileRangeException e) {
    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.size())
        .body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(mmiv.transfer.files.FileStorageException.class)
  public ResponseEntity<Map<String, String>> readFailure(
      mmiv.transfer.files.FileStorageException e) {
    return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, String>> uploadTooLarge() {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(Map.of("error", "File exceeds the upload size limit"));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class
  })
  public ResponseEntity<Map<String, String>> missingParameter() {
    return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Map<String, String>> transferFailure(IllegalStateException e) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(Map.of("error", "Storage transfer failed"));
  }
}
