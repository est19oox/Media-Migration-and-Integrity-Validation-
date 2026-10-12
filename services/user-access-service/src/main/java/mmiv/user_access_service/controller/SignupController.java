package mmiv.user_access_service.controller;

import jakarta.validation.Valid;
import mmiv.user_access_service.dto.SignupRequest;
import mmiv.user_access_service.dto.SignupResponse;
import mmiv.user_access_service.service.RegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class SignupController {
  private final RegistrationService registration;

  public SignupController(RegistrationService registration) {
    this.registration = registration;
  }

  @PostMapping(value = "/signup", consumes = "application/json")
  public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .header("Cache-Control", "no-store")
        .body(registration.register(request));
  }
}
