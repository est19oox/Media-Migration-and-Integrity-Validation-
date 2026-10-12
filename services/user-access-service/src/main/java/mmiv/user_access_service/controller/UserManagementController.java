package mmiv.user_access_service.controller;

import jakarta.validation.Valid;
import mmiv.user_access_service.dto.CreateUserRequest;
import mmiv.user_access_service.dto.SetUserActiveRequest;
import mmiv.user_access_service.dto.UserResponse;
import mmiv.user_access_service.service.UserManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserManagementController {

  private final UserManagementService userManagementService;

  public UserManagementController(UserManagementService userManagementService) {
    this.userManagementService = userManagementService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse createUser(
      @Valid @RequestBody CreateUserRequest request, @AuthenticationPrincipal Jwt jwt) {
    return userManagementService.createUser(request, jwt == null ? null : jwt.getSubject());
  }

  @PatchMapping("/{userId}/active")
  public UserResponse setUserActive(
      @PathVariable String userId,
      @Valid @RequestBody SetUserActiveRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    return userManagementService.setUserActive(
        userId, request.active(), jwt == null ? null : jwt.getSubject());
  }
}
