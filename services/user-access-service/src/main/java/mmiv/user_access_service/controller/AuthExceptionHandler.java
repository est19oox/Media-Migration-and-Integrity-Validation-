package mmiv.user_access_service.controller;

import java.util.Map;
import mmiv.user_access_service.service.InvalidCredentialsException;
import mmiv.user_access_service.service.DuplicateUsernameException;
import mmiv.user_access_service.service.InvalidSignupException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<Map<String, String>> handleInvalidCredentials(
      InvalidCredentialsException exception) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(Map.of("error", "Invalid username or password"));
  }
  @ExceptionHandler(DuplicateUsernameException.class)
  public ResponseEntity<Map<String, String>> duplicateUsername() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("error", "Username is already taken"));
  }

  @ExceptionHandler(InvalidSignupException.class)
  public ResponseEntity<Map<String, String>> invalidSignup() {
    return ResponseEntity.badRequest().body(Map.of("error", "Invalid signup details"));
  }
}
