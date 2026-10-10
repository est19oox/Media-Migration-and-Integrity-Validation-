package mmiv.user_access_service.controller;

import jakarta.validation.Valid;
import mmiv.user_access_service.dto.LoginRequest;
import mmiv.user_access_service.dto.LoginResponse;
import mmiv.user_access_service.service.AuthenticationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthenticationService authenticationService;

  public AuthController(AuthenticationService authenticationService) {
    this.authenticationService = authenticationService;
  }

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {

    String token = authenticationService.login(request.username(), request.password());

    return ResponseEntity.ok(new LoginResponse(token, "Bearer"));
  }
}
