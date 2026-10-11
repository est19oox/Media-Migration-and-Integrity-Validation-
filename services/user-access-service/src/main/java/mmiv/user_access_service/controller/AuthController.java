package mmiv.user_access_service.controller;

import jakarta.validation.Valid;
import mmiv.user_access_service.dto.LoginRequest;
import mmiv.user_access_service.dto.LoginResponse;
import mmiv.user_access_service.model.User;
import mmiv.user_access_service.service.AuthenticationService;
import mmiv.user_access_service.service.JwtService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthenticationService authenticationService;
  private final JwtService jwtService;

  public AuthController(AuthenticationService authenticationService, JwtService jwtService) {
    this.authenticationService = authenticationService;
    this.jwtService = jwtService;
  }

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    User user = authenticationService.authenticate(request.username(), request.password());
    String token = jwtService.generateToken(user);
    return ResponseEntity.ok(new LoginResponse(token, "Bearer"));
  }
}
